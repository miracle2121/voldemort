/*
 * Copyright 2008-2013 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package voldemort.client.rebalance;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

import voldemort.VoldemortException;
import voldemort.client.ClientConfig;
import voldemort.client.protocol.admin.AdminClient;
import voldemort.client.protocol.admin.AdminClientConfig;
import voldemort.client.rebalance.task.DonorBasedRebalanceTask;
import voldemort.client.rebalance.task.RebalanceTask;
import voldemort.client.rebalance.task.StealerBasedRebalanceTask;
import voldemort.cluster.Cluster;
import voldemort.cluster.Node;
import voldemort.server.rebalance.VoldemortRebalancingException;
import voldemort.store.StoreDefinition;
import voldemort.utils.NodeUtils;
import voldemort.utils.Pair;
import voldemort.utils.RebalanceUtils;
import voldemort.utils.StoreDefinitionUtils;
import voldemort.utils.Utils;
import voldemort.versioning.Versioned;

import com.google.common.collect.Lists;

/**
 * Executes a RebalancePlan.
 * 
 */
public class RebalanceController {

    // TODO: Remove server side "optimization" that does not bother to steal
    // partition-stores it already hosts. That code will be unnecessary. This
    // also affects AdminClient that has an override of this option. Do not
    // complete this work until the atomic metadata update is merged with this
    // branch. Otherwise, there will be conflicts on .proto changes.

    private static final Logger logger = Logger.getLogger(RebalanceController.class);

    private static final DecimalFormat decimalFormatter = new DecimalFormat("#.##");

    private final AdminClient adminClient;
    private final Cluster currentCluster;
    private final List<StoreDefinition> currentStoreDefs;

    public final static int MAX_PARALLEL_REBALANCING = Integer.MAX_VALUE;
    public final static int MAX_TRIES_REBALANCING = 2;
    public final static boolean STEALER_BASED_REBALANCING = true;
    public final static long PROXY_PAUSE_IN_SECONDS = TimeUnit.MINUTES.toSeconds(5);

    private final int maxParallelRebalancing;
    private final int maxTriesRebalancing;
    private final boolean stealerBasedRebalancing;
    private final long proxyPauseSec;

    public RebalanceController(String bootstrapUrl,
                               int maxParallelRebalancing,
                               int maxTriesRebalancing,
                               boolean stealerBased,
                               long proxyPauseSec) {
        this.adminClient = new AdminClient(bootstrapUrl,
                                           new AdminClientConfig(),
                                           new ClientConfig());
        Pair<Cluster, List<StoreDefinition>> pair = getCurrentClusterState();
        this.currentCluster = pair.getFirst();
        this.currentStoreDefs = pair.getSecond();

        this.maxParallelRebalancing = maxParallelRebalancing;
        this.maxTriesRebalancing = maxTriesRebalancing;
        this.stealerBasedRebalancing = stealerBased;
        this.proxyPauseSec = proxyPauseSec;
    }

    /**
     * Probe the existing cluster to retrieve the current cluster xml and stores
     * xml.
     * 
     * @return Pair of Cluster and List<StoreDefinition> from current cluster.
     */
    private Pair<Cluster, List<StoreDefinition>> getCurrentClusterState() {

        // Retrieve the latest cluster metadata from the existing nodes
        Versioned<Cluster> currentVersionedCluster = RebalanceUtils.getLatestCluster(NodeUtils.getNodeIds(Lists.newArrayList(adminClient.getAdminClientCluster()
                                                                                                                                        .getNodes())),
                                                                                     adminClient);
        Cluster cluster = currentVersionedCluster.getValue();
        List<StoreDefinition> storeDefs = RebalanceUtils.getCurrentStoreDefinitions(cluster,
                                                                                    adminClient);
        return new Pair<Cluster, List<StoreDefinition>>(cluster, storeDefs);
    }

    /**
     * Construct a plan for the specified final cluster/stores & batchSize given
     * the current cluster/stores & configuration of the RebalanceController.
     * 
     * @param finalCluster
     * @param finalStoreDefs
     *            Needed for zone expansion/shrinking.
     * @param batchSize
     * @return
     */
    public RebalancePlan getPlan(Cluster finalCluster,
                                 List<StoreDefinition> finalStoreDefs,
                                 int batchSize) {
        RebalanceUtils.validateClusterStores(finalCluster, finalStoreDefs);
        // If an interim cluster is needed, then currentCluster should be an
        // interim cluster! I.e., it should include new nodes/zones without any
        // partitions assigned to them.
        RebalanceUtils.validateInterimFinalCluster(currentCluster, finalCluster);

        String outputDir = null;
        return new RebalancePlan(currentCluster,
                                 currentStoreDefs,
                                 finalCluster,
                                 finalStoreDefs,
                                 batchSize,
                                 outputDir);
    }

    /**
     * Construct a plan for the specified final cluster & batchSize given the
     * current cluster/stores & configuration of the RebalanceController.
     * 
     * @param finalCluster
     * @param batchSize
     * @return
     */
    public RebalancePlan getPlan(Cluster finalCluster, int batchSize) {
        return getPlan(finalCluster, currentStoreDefs, batchSize);
    }

    /**
     * Validate the plan, validate the cluster, and then kick off rebalance.
     * 
     * @param rebalancePlan
     */
    public void rebalance(final RebalancePlan rebalancePlan) {
        validateRebalancePlanState(rebalancePlan);
        validateClusterForRebalance(rebalancePlan);
        executePlan(rebalancePlan);
    }

    /**
     * Validates all aspects of the plan (i.e., all config files)
     * 
     * @param rebalancePlan
     */
    private void validateRebalancePlanState(RebalancePlan rebalancePlan) {
        logger.info("Validating rebalance plan state.");

        Cluster currentCluster = rebalancePlan.getCurrentCluster();
        List<StoreDefinition> currentStores = rebalancePlan.getCurrentStores();
        Cluster finalCluster = rebalancePlan.getFinalCluster();
        List<StoreDefinition> finalStores = rebalancePlan.getFinalStores();

        RebalanceUtils.validateClusterStores(currentCluster, currentStores);
        RebalanceUtils.validateClusterStores(finalCluster, finalStores);
        RebalanceUtils.validateCurrentFinalCluster(currentCluster, finalCluster);
        RebalanceUtils.validateRebalanceStore(currentStores);
        RebalanceUtils.validateRebalanceStore(finalStores);
    }

    /**
     * Validates deployed cluster:
     * <ul>
     * <li>sets local admin client to finalCluster
     * <li>checks that all servers are currently in normal state
     * <li>confirms read-only stores can be rebalanced.
     * </ul>
     * 
     * @param finalCluster
     * @param finalStores
     */
    private void validateClusterForRebalance(RebalancePlan rebalancePlan) {
        logger.info("Validating state of deployed cluster to confirm its ready for rebalance.");
        Cluster finalCluster = rebalancePlan.getFinalCluster();
        List<StoreDefinition> finalStoreDefs = rebalancePlan.getFinalStores();

        // Reset the cluster that the admin client points at
        adminClient.setAdminClientCluster(finalCluster);
        // Validate that all the nodes ( new + old ) are in normal state
        RebalanceUtils.checkEachServerInNormalState(finalCluster, adminClient);
        // Verify all old RO stores exist at version 2
        RebalanceUtils.validateReadOnlyStores(finalCluster,
                                              finalStoreDefs,
                                              adminClient);
    }

    /**
     * Executes the rebalance plan. Does so batch-by-batch. Between each batch,
     * status is dumped to logger.info.
     * 
     * @param rebalancePlan
     */
    private void executePlan(RebalancePlan rebalancePlan) {
        logger.info("Starting to execute rebalance Plan!");

        int batchCount = 0;
        int partitionStoreCount = 0;
        long totalTimeMs = 0;

        List<RebalanceBatchPlan> entirePlan = rebalancePlan.getPlan();
        int numBatches = entirePlan.size();
        int numPartitionStores = rebalancePlan.getPartitionStoresMoved();

        for (RebalanceBatchPlan batchPlan : entirePlan) {
            logger.info("========  REBALANCING BATCH " + (batchCount + 1)
                    + "  ========");
            RebalanceUtils.printBatchLog(batchCount,
                                         logger,
                                         batchPlan.toString());

            long startTimeMs = System.currentTimeMillis();
            // ACTUALLY DO A BATCH OF REBALANCING!
            executeBatch(batchCount, batchPlan);
            totalTimeMs += (System.currentTimeMillis() - startTimeMs);

            // Bump up the statistics
            batchCount++;
            partitionStoreCount += batchPlan.getPartitionStoreMoves();
            batchStatusLog(batchCount,
                           numBatches,
                           partitionStoreCount,
                           numPartitionStores,
                           totalTimeMs);
        }
    }

    /**
     * Pretty print a progress update after each batch complete.
     * 
     * @param batchCount
     *            current batch
     * @param numBatches
     *            total number of batches
     * @param partitionStoreCount
     *            partition stores migrated
     * @param numPartitionStores
     *            total number of partition stores to migrate
     * @param totalTimeMs
     *            total time, in milliseconds, of execution thus far.
     */
    private void batchStatusLog(int batchCount,
                                int numBatches,
                                int partitionStoreCount,
                                int numPartitionStores,
                                long totalTimeMs) {
        // Calculate the estimated end time and pretty print stats
        double rate = 1;
        long estimatedTimeMs = 0;
        if (numPartitionStores > 0) {
            rate = partitionStoreCount / numPartitionStores;
            estimatedTimeMs = (long) (totalTimeMs / rate) - totalTimeMs;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Batch Complete!")
          .append(Utils.NEWLINE)
          .append("\tbatches moved: ")
          .append(batchCount)
          .append(" out of ")
          .append(numBatches)
          .append(Utils.NEWLINE)
          .append("\tPartition stores moved: ")
          .append(partitionStoreCount)
          .append(" out of ")
          .append(numPartitionStores)
          .append(Utils.NEWLINE)
          .append("\tPercent done: ")
          .append(decimalFormatter.format(rate * 100.0))
          .append(Utils.NEWLINE)
          .append("\tEstimated time left: ")
          .append(estimatedTimeMs)
          .append(" ms (")
          .append(TimeUnit.MILLISECONDS.toHours(estimatedTimeMs))
          .append(" hours)");
        RebalanceUtils.printBatchLog(batchCount, logger, sb.toString());
    }

    /**
     * Executes a batch plan.
     * 
     * @param batchId
     *            Used as the ID of the batch plan. This allows related tasks on
     *            client- & server-side to pretty print messages in a manner
     *            that debugging can track specific batch plans across the
     *            cluster.
     * @param batchPlan
     *            The batch plan...
     */
    private void executeBatch(int batchId, final RebalanceBatchPlan batchPlan) {
        final Cluster batchCurrentCluster = batchPlan.getCurrentCluster();
        final List<StoreDefinition> batchCurrentStoreDefs = batchPlan.getCurrentStoreDefs();
        final Cluster batchFinalCluster = batchPlan.getFinalCluster();
        final List<StoreDefinition> batchFinalStoreDefs = batchPlan.getFinalStoreDefs();

        try {
            final List<RebalancePartitionsInfo> rebalancePartitionsInfoList = batchPlan.getBatchPlan();

            if (rebalancePartitionsInfoList.isEmpty()) {
                RebalanceUtils.printBatchLog(batchId, logger, "Skipping batch "
                        + batchId + " since it is empty.");
                // Even though there is no rebalancing work to do, cluster
                // metadata must be updated so that the server is aware of the
                // new cluster xml.
                adminClient.rebalanceOps.rebalanceStateChange(batchCurrentCluster,
                                                              batchFinalCluster,
                                                              batchCurrentStoreDefs,
                                                              batchFinalStoreDefs,
                                                              rebalancePartitionsInfoList,
                                                              false,
                                                              true,
                                                              false,
                                                              false,
                                                              true);
                return;
            }

            RebalanceUtils.printBatchLog(batchId, logger, "Starting batch "
                    + batchId + ".");

            // Split the store definitions
            List<StoreDefinition> readOnlyStoreDefs = StoreDefinitionUtils.filterStores(batchFinalStoreDefs,
                                                                                        true);
            List<StoreDefinition> readWriteStoreDefs = StoreDefinitionUtils.filterStores(batchFinalStoreDefs,
                                                                                         false);
            boolean hasReadOnlyStores = readOnlyStoreDefs != null
                    && readOnlyStoreDefs.size() > 0;
            boolean hasReadWriteStores = readWriteStoreDefs != null
                    && readWriteStoreDefs.size() > 0;

            // STEP 1 - Cluster state change
            boolean finishedReadOnlyPhase = false;
            List<RebalancePartitionsInfo> filteredRebalancePartitionPlanList = RebalanceUtils.filterPartitionPlanWithStores(rebalancePartitionsInfoList,
                                                                                                                            readOnlyStoreDefs);

            rebalanceStateChange(batchId,
                                 batchCurrentCluster,
                                 batchCurrentStoreDefs,
                                 batchFinalCluster,
                                 batchFinalStoreDefs,
                                 filteredRebalancePartitionPlanList,
                                 hasReadOnlyStores,
                                 hasReadWriteStores,
                                 finishedReadOnlyPhase);

            // STEP 2 - Move RO data
            if (hasReadOnlyStores) {
                RebalanceBatchPlanProgressBar progressBar = batchPlan.getProgressBar(batchId);
                executeSubBatch(batchId,
                                progressBar,
                                batchCurrentCluster,
                                batchCurrentStoreDefs,
                                filteredRebalancePartitionPlanList,
                                hasReadOnlyStores,
                                hasReadWriteStores,
                                finishedReadOnlyPhase);
            }

            // STEP 3 - Cluster change state
            finishedReadOnlyPhase = true;
            filteredRebalancePartitionPlanList = RebalanceUtils.filterPartitionPlanWithStores(rebalancePartitionsInfoList,
                                                                                              readWriteStoreDefs);

            rebalanceStateChange(batchId,
                                 batchCurrentCluster,
                                 batchCurrentStoreDefs,
                                 batchFinalCluster,
                                 batchFinalStoreDefs,
                                 filteredRebalancePartitionPlanList,
                                 hasReadOnlyStores,
                                 hasReadWriteStores,
                                 finishedReadOnlyPhase);

            // STEP 4 - Move RW data
            if (hasReadWriteStores) {
                proxyPause();
                RebalanceBatchPlanProgressBar progressBar = batchPlan.getProgressBar(batchId);
                executeSubBatch(batchId,
                                progressBar,
                                batchCurrentCluster,
                                batchCurrentStoreDefs,
                                filteredRebalancePartitionPlanList,
                                hasReadOnlyStores,
                                hasReadWriteStores,
                                finishedReadOnlyPhase);
            }

            RebalanceUtils.printBatchLog(batchId,
                                         logger,
                                         "Successfully terminated batch "
                                                 + batchId + ".");

        } catch (Exception e) {
            RebalanceUtils.printErrorLog(batchId, logger, "Error in batch "
                    + batchId + " - " + e.getMessage(), e);
            throw new VoldemortException("Rebalance failed on batch " + batchId,
                                         e);
        }
    }

    /**
     * Pause between cluster change in metadata and starting server rebalancing
     * work.
     */
    private void proxyPause() {
        logger.info("Pausing after cluster state has changed to allow proxy bridges to be established. "
                + "Will start rebalancing work on servers in "
                + proxyPauseSec
                + " seconds.");
        try {
            Thread.sleep(TimeUnit.SECONDS.toMillis(proxyPauseSec));
        } catch (InterruptedException e) {
            logger.warn("Sleep interrupted in proxy pause.");
        }
    }

    /**
     * 
     * Perform a group of state change actions. Also any errors + rollback
     * procedures are performed at this level itself.
     * 
     * <pre>
     * | Case | hasRO | hasRW | finishedRO | Action |
     * | 0 | t | t | t | 2nd one ( cluster change + swap + rebalance state change ) |
     * | 1 | t | t | f | 1st one ( rebalance state change ) |
     * | 2 | t | f | t | 2nd one ( cluster change + swap ) |
     * | 3 | t | f | f | 1st one ( rebalance state change ) |
     * | 4 | f | t | t | 2nd one ( cluster change + rebalance state change ) |
     * | 5 | f | t | f | ignore |
     * | 6 | f | f | t | no stores, exception | 
     * | 7 | f | f | f | no stores, exception |
     * </pre>
     * 
     * Truth table, FTW!
     * 
     * @param batchId
     *            Rebalancing batch id
     * @param batchCurrentCluster
     *            Current cluster
     * @param batchFinalCluster
     *            Transition cluster to propagate
     * @param rebalancePartitionPlanList
     *            List of partition plan list
     * @param hasReadOnlyStores
     *            Boolean indicating if read-only stores exist
     * @param hasReadWriteStores
     *            Boolean indicating if read-write stores exist
     * @param finishedReadOnlyStores
     *            Boolean indicating if we have finished RO store migration
     */
    private void
            rebalanceStateChange(final int batchId,
                                 Cluster batchCurrentCluster,
                                 List<StoreDefinition> batchCurrentStoreDefs,
                                 Cluster batchFinalCluster,
                                 List<StoreDefinition> batchFinalStoreDefs,
                                 List<RebalancePartitionsInfo> rebalancePartitionPlanList,
                                 boolean hasReadOnlyStores,
                                 boolean hasReadWriteStores,
                                 boolean finishedReadOnlyStores) {
        try {
            if (!hasReadOnlyStores && !hasReadWriteStores) {
                // Case 6 / 7 - no stores, exception
                throw new VoldemortException("Cannot get this state since it means there are no stores");
            } else if (!hasReadOnlyStores && hasReadWriteStores
                    && !finishedReadOnlyStores) {
                // Case 5 - ignore
                RebalanceUtils.printBatchLog(batchId,
                                             logger,
                                             "Ignoring state change since there are no read-only stores");
            } else if (!hasReadOnlyStores && hasReadWriteStores
                    && finishedReadOnlyStores) {
                // Case 4 - cluster change + rebalance state change
                RebalanceUtils.printBatchLog(batchId,
                                             logger,
                                             "Cluster metadata change + rebalance state change");
                adminClient.rebalanceOps.rebalanceStateChange(batchCurrentCluster,
                                                              batchFinalCluster,
                                                              batchCurrentStoreDefs,
                                                              batchFinalStoreDefs,
                                                              rebalancePartitionPlanList,
                                                              false,
                                                              true,
                                                              true,
                                                              true,
                                                              true);
            } else if (hasReadOnlyStores && !finishedReadOnlyStores) {
                // Case 1 / 3 - rebalance state change
                RebalanceUtils.printBatchLog(batchId,
                                             logger,
                                             "Rebalance state change");
                adminClient.rebalanceOps.rebalanceStateChange(batchCurrentCluster,
                                                              batchFinalCluster,
                                                              batchCurrentStoreDefs,
                                                              batchFinalStoreDefs,
                                                              rebalancePartitionPlanList,
                                                              false,
                                                              false,
                                                              true,
                                                              true,
                                                              true);
            } else if (hasReadOnlyStores && !hasReadWriteStores
                    && finishedReadOnlyStores) {
                // Case 2 - swap + cluster change
                RebalanceUtils.printBatchLog(batchId,
                                             logger,
                                             "Swap + Cluster metadata change");
                adminClient.rebalanceOps.rebalanceStateChange(batchCurrentCluster,
                                                              batchFinalCluster,
                                                              batchCurrentStoreDefs,
                                                              batchFinalStoreDefs,
                                                              rebalancePartitionPlanList,
                                                              true,
                                                              true,
                                                              false,
                                                              true,
                                                              true);
            } else {
                // Case 0 - swap + cluster change + rebalance state change
                RebalanceUtils.printBatchLog(batchId,
                                             logger,
                                             "Swap + Cluster metadata change + rebalance state change");
                adminClient.rebalanceOps.rebalanceStateChange(batchCurrentCluster,
                                                              batchFinalCluster,
                                                              batchCurrentStoreDefs,
                                                              batchFinalStoreDefs,
                                                              rebalancePartitionPlanList,
                                                              true,
                                                              true,
                                                              true,
                                                              true,
                                                              true);
            }

        } catch (VoldemortRebalancingException e) {
            RebalanceUtils.printErrorLog(batchId,
                                         logger,
                                         "Failure while changing rebalancing state",
                                         e);
            throw e;
        }
    }

    // TODO: (refactor) Break this state-machine like method into multiple "sub"
    // methods. AFAIK, this method either does the RO stores or the RW stores in
    // a batch. I.e., there are at most 2 sub-batches for any given batch. And,
    // in practice, there is one sub-batch that is either RO or RW.
    // TODO: Fix the javadoc comment to be more easily understood.
    /**
     * The smallest granularity of rebalancing where-in we move partitions for a
     * sub-set of stores. Finally at the end of the movement, the node is
     * removed out of rebalance state
     * 
     * <br>
     * 
     * Also any errors + rollback procedures are performed at this level itself.
     * 
     * <pre>
     * | Case | hasRO | hasRW | finishedRO | Action |
     * | 0 | t | t | t | rollback cluster change + swap |
     * | 1 | t | t | f | nothing to do since "rebalance state change" should have removed everything |
     * | 2 | t | f | t | won't be triggered since hasRW is false |
     * | 3 | t | f | f | nothing to do since "rebalance state change" should have removed everything |
     * | 4 | f | t | t | rollback cluster change |
     * | 5 | f | t | f | won't be triggered |
     * | 6 | f | f | t | won't be triggered | 
     * | 7 | f | f | f | won't be triggered |
     * </pre>
     * 
     * @param batchId
     *            Rebalance batch id
     * @param batchRollbackCluster
     *            Cluster to rollback to if we have a problem
     * @param rebalancePartitionPlanList
     *            The list of rebalance partition plans
     * @param hasReadOnlyStores
     *            Are we rebalancing any read-only stores?
     * @param hasReadWriteStores
     *            Are we rebalancing any read-write stores?
     * @param finishedReadOnlyStores
     *            Have we finished rebalancing of read-only stores?
     */
    private void
            executeSubBatch(final int batchId,
                            RebalanceBatchPlanProgressBar progressBar,
                            final Cluster batchRollbackCluster,
                            final List<StoreDefinition> batchRollbackStoreDefs,
                            final List<RebalancePartitionsInfo> rebalancePartitionPlanList,
                            boolean hasReadOnlyStores,
                            boolean hasReadWriteStores,
                            boolean finishedReadOnlyStores) {
        RebalanceUtils.printBatchLog(batchId,
                                     logger,
                                     "Submitting rebalance tasks ");

        // Get an ExecutorService in place used for submitting our tasks
        ExecutorService service = RebalanceUtils.createExecutors(maxParallelRebalancing);

        // Sub-list of the above list
        final List<RebalanceTask> failedTasks = Lists.newArrayList();
        final List<RebalanceTask> incompleteTasks = Lists.newArrayList();

        // Semaphores for donor nodes - To avoid multiple disk sweeps
        Semaphore[] donorPermits = new Semaphore[batchRollbackCluster.getNumberOfNodes()];
        for (Node node : batchRollbackCluster.getNodes()) {
            donorPermits[node.getId()] = new Semaphore(1);
        }

        try {
            // List of tasks which will run asynchronously
            List<RebalanceTask> allTasks = executeTasks(batchId,
                                                        progressBar,
                                                        service,
                                                        rebalancePartitionPlanList,
                                                        donorPermits);
            RebalanceUtils.printBatchLog(batchId,
                                         logger,
                                         "All rebalance tasks submitted");

            // Wait and shutdown after (infinite) timeout
            RebalanceUtils.executorShutDown(service, Long.MAX_VALUE);
            RebalanceUtils.printBatchLog(batchId,
                                         logger,
                                         "Finished waiting for executors");

            // Collects all failures + incomplete tasks from the rebalance
            // tasks.
            List<Exception> failures = Lists.newArrayList();
            for (RebalanceTask task : allTasks) {
                if (task.hasException()) {
                    failedTasks.add(task);
                    failures.add(task.getError());
                } else if (!task.isComplete()) {
                    incompleteTasks.add(task);
                }
            }

            if (failedTasks.size() > 0) {
                throw new VoldemortRebalancingException("Rebalance task terminated unsuccessfully on tasks "
                                                                + failedTasks,
                                                        failures);
            }

            // If there were no failures, then we could have had a genuine
            // timeout ( Rebalancing took longer than the operator expected ).
            // We should throw a VoldemortException and not a
            // VoldemortRebalancingException ( which will start reverting
            // metadata ). The operator may want to manually then resume the
            // process.
            if (incompleteTasks.size() > 0) {
                throw new VoldemortException("Rebalance tasks are still incomplete / running "
                        + incompleteTasks);
            }

        } catch (VoldemortRebalancingException e) {

            logger.error("Failure while migrating partitions for rebalance task "
                    + batchId);

            if (hasReadOnlyStores && hasReadWriteStores
                    && finishedReadOnlyStores) {
                // Case 0
                adminClient.rebalanceOps.rebalanceStateChange(null,
                                                              batchRollbackCluster,
                                                              null,
                                                              batchRollbackStoreDefs,
                                                              null,
                                                              true,
                                                              true,
                                                              false,
                                                              false,
                                                              false);
            } else if (hasReadWriteStores && finishedReadOnlyStores) {
                // Case 4
                adminClient.rebalanceOps.rebalanceStateChange(null,
                                                              batchRollbackCluster,
                                                              null,
                                                              batchRollbackStoreDefs,
                                                              null,
                                                              false,
                                                              true,
                                                              false,
                                                              false,
                                                              false);
            }

            throw e;

        } finally {
            if (!service.isShutdown()) {
                RebalanceUtils.printErrorLog(batchId,
                                             logger,
                                             "Could not shutdown service cleanly for rebalance task "
                                                     + batchId,
                                             null);
                service.shutdownNow();
            }
        }
    }

    // TODO: (refactor) Move RebalanceController scheduler into its own class.
    // TODO: Add unit tests for RebalanceController.scheduler.
    // TODO: This scheduler deprecates the need for donor permits. Consider
    // removing them.
    /**
     * Scheduler for rebalancing tasks. There is at most one rebalancing task
     * per stealer-donor pair. This scheduler ensures the following invariant is
     * obeyed:
     * 
     * A node works on no more than one rebalancing task at a time.
     * 
     * Note that a node working on a rebalancing task may be either a stealer or
     * a donor. This invariant should somewhat isolate the foreground workload
     * against the work a server must do for rebalancing. Because of this
     * isolation, it is safe to attempt "infinite" parallelism since no more
     * than floor(number of nodes / 2) rebalancing tasks can possibly be
     * scheduled to execute while obeying the invariant.
     * 
     * The order of tasks are randomized within this class. The intent is to
     * "spread" rebalancing work smoothly out over the cluster and avoid
     * "long tails" of straggler rebalancing tasks. Only experience will tell us
     * if we need to do anything smarter.
     */
    public class Scheduler {

        final private ExecutorService service;

        private Map<Integer, List<StealerBasedRebalanceTask>> tasksByStealer;
        private int numTasksExecuting;
        private Set<Integer> nodeIdsWithWork;
        private CountDownLatch doneSignal;

        Scheduler(ExecutorService service) {
            this.service = service;
        }

        /**
         * Set up scheduling structures and then start scheduling tasks to
         * execute. Blocks until all tasks have been scheduled. (For all tasks
         * to be scheduled, most tasks must have completed.)
         * 
         * @param sbTaskList
         *            List of all stealer-based rebalancing tasks to be
         *            scheduled.
         */
        public void run(List<StealerBasedRebalanceTask> sbTaskList) {
            // Setup mapping of stealers to work for this run.
            this.tasksByStealer = new HashMap<Integer, List<StealerBasedRebalanceTask>>();
            for (StealerBasedRebalanceTask task : sbTaskList) {
                if (task.getStealInfos().size() != 1) {
                    throw new VoldemortException("StealerBasedRebalanceTasks should have a list of RebalancePartitionsInfo of length 1.");
                }

                RebalancePartitionsInfo stealInfo = task.getStealInfos().get(0);
                int stealerId = stealInfo.getStealerId();
                if (!this.tasksByStealer.containsKey(stealerId)) {
                    this.tasksByStealer.put(stealerId,
                                            new ArrayList<StealerBasedRebalanceTask>());
                }
                this.tasksByStealer.get(stealerId).add(task);
            }

            if (tasksByStealer.isEmpty()) {
                return;
            }

            // Shuffle order of each stealer's work list. This randomization
            // helps to get rid of any "patterns" in how rebalancing tasks were
            // added to the task list passed in.
            for (List<StealerBasedRebalanceTask> taskList : tasksByStealer.values()) {
                Collections.shuffle(taskList);
            }

            // Prepare to execute the rebalance
            this.numTasksExecuting = 0;
            this.nodeIdsWithWork = new HashSet<Integer>();
            doneSignal = new CountDownLatch(sbTaskList.size());

            // Start scheduling tasks to execute!
            scheduleMoreTasks();

            try {
                doneSignal.await();
            } catch (InterruptedException e) {
                logger.error("RebalancController scheduler interrupted while waiting for rebalance tasks to be scheduled.",
                             e);
                throw new VoldemortRebalancingException("RebalancController scheduler interrupted while waiting for rebalance tasks to be scheduled.");

            }
        }

        /**
         * Schedule as many tasks as possible.
         * 
         * To schedule a task is to make it available to the executor service to
         * run.
         * 
         * "As many tasks as possible" is limited by (i) the parallelism level
         * permitted and (ii) the invariant that a node shall be part of at most
         * one rebalancing task at a time (as either stealer or donor).
         */
        private synchronized void scheduleMoreTasks() {
            RebalanceTask scheduledTask = scheduleNextTask();
            while (scheduledTask != null) {
                scheduledTask = scheduleNextTask();
            }
        }

        /**
         * Schedule at most one task.
         * 
         * The scheduled task *must* invoke 'doneTask()' upon
         * completion/termination.
         * 
         * @return The task scheduled or null if not possible to schedule a task
         *         at this time.
         */
        private synchronized StealerBasedRebalanceTask scheduleNextTask() {
            // Make sure there is work left to do.
            if (doneSignal.getCount() == 0) {
                return null;
            }

            // Limit number of tasks outstanding.
            if (this.numTasksExecuting == maxParallelRebalancing) {
                return null;
            }

            // Shuffle list of stealer IDs each time a new task to schedule
            // needs to be found. Randomizing the order should avoid
            // prioritizing one specific stealer's work ahead of all others.
            List<Integer> stealerIds = new ArrayList<Integer>(tasksByStealer.keySet());
            Collections.shuffle(stealerIds);
            for (int stealerId : stealerIds) {
                if (nodeIdsWithWork.contains(stealerId)) {
                    continue;
                }
                for (StealerBasedRebalanceTask sbTask : tasksByStealer.get(stealerId)) {
                    int donorId = sbTask.getStealInfos().get(0).getDonorId();
                    if (nodeIdsWithWork.contains(donorId)) {
                        continue;
                    }

                    // Bookkeeping for task about to execute:
                    nodeIdsWithWork.add(stealerId);
                    nodeIdsWithWork.add(donorId);
                    numTasksExecuting++;
                    // Remove this task from list thus destroying list
                    // being iterated over. This is safe because returning
                    // directly out of this branch.
                    tasksByStealer.get(stealerId).remove(sbTask);

                    try {
                        service.execute(sbTask);
                    } catch (RejectedExecutionException ree) {
                        logger.error("Rebalancing task rejected by executor service.",
                                     ree);
                        throw new VoldemortRebalancingException("Rebalancing task rejected by executor service.");
                    }

                    return sbTask;
                }
            }

            return null;
        }

        /**
         * Method must be invoked upon completion of a rebalancing task. It is
         * the task's responsibility to do so.
         * 
         * @param stealerId
         * @param donorId
         */
        public synchronized void doneTask(int stealerId, int donorId) {
            // Bookkeeping for completed task :
            nodeIdsWithWork.remove(stealerId);
            nodeIdsWithWork.remove(donorId);
            numTasksExecuting--;

            doneSignal.countDown();

            // Try and schedule more tasks now that resources may be available
            // to do so.
            scheduleMoreTasks();
        }
    }

    private List<RebalanceTask>
            executeTasks(final int batchId,
                         RebalanceBatchPlanProgressBar progressBar,
                         final ExecutorService service,
                         List<RebalancePartitionsInfo> rebalancePartitionPlanList,
                         Semaphore[] donorPermits) {
        List<RebalanceTask> taskList = Lists.newArrayList();
        int taskId = 0;
        if (stealerBasedRebalancing) {
            Scheduler scheduler = new Scheduler(service);
            List<StealerBasedRebalanceTask> sbTaskList = Lists.newArrayList();
            for (RebalancePartitionsInfo partitionsInfo : rebalancePartitionPlanList) {
                StealerBasedRebalanceTask rebalanceTask = new StealerBasedRebalanceTask(batchId,
                                                                                        taskId,
                                                                                        partitionsInfo,
                                                                                        maxTriesRebalancing,
                                                                                        donorPermits[partitionsInfo.getDonorId()],
                                                                                        adminClient,
                                                                                        progressBar,
                                                                                        scheduler);
                taskList.add(rebalanceTask);
                sbTaskList.add(rebalanceTask);
                // service.execute(rebalanceTask);
                taskId++;
            }
            scheduler.run(sbTaskList);
        } else {
            // Group by donor nodes
            HashMap<Integer, List<RebalancePartitionsInfo>> donorNodeBasedPartitionsInfo = RebalanceUtils.groupPartitionsInfoByNode(rebalancePartitionPlanList,
                                                                                                                                    false);
            for (Entry<Integer, List<RebalancePartitionsInfo>> entries : donorNodeBasedPartitionsInfo.entrySet()) {
                DonorBasedRebalanceTask rebalanceTask = new DonorBasedRebalanceTask(batchId,
                                                                                    taskId,
                                                                                    entries.getValue(),
                                                                                    donorPermits[entries.getValue()
                                                                                                        .get(0)
                                                                                                        .getDonorId()],
                                                                                    adminClient,
                                                                                    progressBar);
                taskList.add(rebalanceTask);
                service.execute(rebalanceTask);
                taskId++;
            }
        }
        return taskList;
    }

    public AdminClient getAdminClient() {
        return adminClient;
    }

    public Cluster getCurrentCluster() {
        return currentCluster;
    }

    public List<StoreDefinition> getCurrentStoreDefs() {
        return currentStoreDefs;
    }

    public void stop() {
        adminClient.close();
    }

}
