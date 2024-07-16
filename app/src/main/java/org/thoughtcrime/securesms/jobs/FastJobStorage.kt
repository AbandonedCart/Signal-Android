package org.thoughtcrime.securesms.jobs

import androidx.annotation.VisibleForTesting
import org.thoughtcrime.securesms.database.JobDatabase
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.persistence.ConstraintSpec
import org.thoughtcrime.securesms.jobmanager.persistence.DependencySpec
import org.thoughtcrime.securesms.jobmanager.persistence.FullSpec
import org.thoughtcrime.securesms.jobmanager.persistence.JobSpec
import org.thoughtcrime.securesms.jobmanager.persistence.JobStorage
import org.thoughtcrime.securesms.util.LRUCache
import java.util.TreeSet

class FastJobStorage(private val jobDatabase: JobDatabase) : JobStorage {

  companion object {
    private const val JOB_CACHE_LIMIT = 1000
  }

  private val jobSpecCache: LRUCache<String, JobSpec> = LRUCache(JOB_CACHE_LIMIT)

  private val jobs: MutableList<MinimalJobSpec> = mutableListOf()

  // TODO [job] Rather than duplicate what is likely the same handful of constraints over and over, we should somehow re-use instances
  private val constraintsByJobId: MutableMap<String, MutableList<ConstraintSpec>> = mutableMapOf()
  private val dependenciesByJobId: MutableMap<String, MutableList<DependencySpec>> = mutableMapOf()

  private val eligibleJobs: TreeSet<MinimalJobSpec> = TreeSet(EligibleJobComparator)
  private val migrationJobs: TreeSet<MinimalJobSpec> = TreeSet(compareBy { it.createTime })
  private val mostEligibleJobForQueue: MutableMap<String, MinimalJobSpec> = hashMapOf()

  @Synchronized
  override fun init() {
    jobs += jobDatabase.getAllMinimalJobSpecs()

    for (job in jobs) {
      if (job.queueKey == Job.Parameters.MIGRATION_QUEUE_KEY) {
        migrationJobs += job
      } else {
        placeJobInEligibleList(job)
      }
    }

    jobDatabase.getOldestJobSpecs(JOB_CACHE_LIMIT).forEach {
      jobSpecCache[it.id] = it
    }

    for (constraintSpec in jobDatabase.getAllConstraintSpecs()) {
      val jobConstraints: MutableList<ConstraintSpec> = constraintsByJobId.getOrPut(constraintSpec.jobSpecId) { mutableListOf() }
      jobConstraints += constraintSpec
    }

    for (dependencySpec in jobDatabase.getAllDependencySpecs().filterNot { it.hasCircularDependency() }) {
      val jobDependencies: MutableList<DependencySpec> = dependenciesByJobId.getOrPut(dependencySpec.jobId) { mutableListOf() }
      jobDependencies += dependencySpec
    }
  }

  @Synchronized
  override fun insertJobs(fullSpecs: List<FullSpec>) {
    val durable: List<FullSpec> = fullSpecs.filterNot { it.isMemoryOnly }

    if (durable.isNotEmpty()) {
      jobDatabase.insertJobs(durable)
    }

    for (fullSpec in fullSpecs) {
      val minimalJobSpec = fullSpec.jobSpec.toMinimalJobSpec()
      jobs += minimalJobSpec
      jobSpecCache[fullSpec.jobSpec.id] = fullSpec.jobSpec

      if (fullSpec.jobSpec.queueKey == Job.Parameters.MIGRATION_QUEUE_KEY) {
        migrationJobs += minimalJobSpec
      } else {
        placeJobInEligibleList(minimalJobSpec)
      }

      constraintsByJobId[fullSpec.jobSpec.id] = fullSpec.constraintSpecs.toMutableList()
      dependenciesByJobId[fullSpec.jobSpec.id] = fullSpec.dependencySpecs.toMutableList()
    }
  }

  @Synchronized
  override fun getJobSpec(id: String): JobSpec? {
    return jobs.firstOrNull { it.id == id }?.toJobSpec()
  }

  @Synchronized
  override fun getAllJobSpecs(): List<JobSpec> {
    // TODO [job] this will have to change
    return jobDatabase.getAllJobSpecs()
  }

  @Synchronized
  override fun getPendingJobsWithNoDependenciesInCreatedOrder(currentTime: Long): List<JobSpec> {
    val migrationJob: MinimalJobSpec? = migrationJobs.firstOrNull()

    return if (migrationJob != null && !migrationJob.isRunning && migrationJob.hasEligibleRunTime(currentTime)) {
      listOf(migrationJob.toJobSpec())
    } else if (migrationJob != null) {
      emptyList()
    } else {
      eligibleJobs
        .asSequence()
        .filter { job ->
          // Filter out all jobs with unmet dependencies
          dependenciesByJobId[job.id].isNullOrEmpty()
        }
        .filterNot { it.isRunning }
        .filter { job -> job.hasEligibleRunTime(currentTime) }
        .map { it.toJobSpec() }
        .toList()
    }
  }

  @Synchronized
  override fun getJobsInQueue(queue: String): List<JobSpec> {
    return jobs
      .filter { it.queueKey == queue }
      .map { it.toJobSpec() }
  }

  @Synchronized
  override fun getJobCountForFactory(factoryKey: String): Int {
    return jobs
      .filter { it.factoryKey == factoryKey }
      .size
  }

  @Synchronized
  override fun getJobCountForFactoryAndQueue(factoryKey: String, queueKey: String): Int {
    return jobs
      .filter { it.factoryKey == factoryKey && it.queueKey == queueKey }
      .size
  }

  @Synchronized
  override fun areQueuesEmpty(queueKeys: Set<String>): Boolean {
    return jobs.none { it.queueKey != null && queueKeys.contains(it.queueKey) }
  }

  @Synchronized
  override fun markJobAsRunning(id: String, currentTime: Long) {
    val job: JobSpec? = getJobSpec(id)
    if (job == null || !job.isMemoryOnly) {
      jobDatabase.markJobAsRunning(id, currentTime)
      // Don't need to update jobSpecCache because all changed fields are in the min spec
    }

    updateCachedJobSpecs(
      filter = { it.id == id },
      transformer = { jobSpec ->
        jobSpec.copy(
          isRunning = true,
          lastRunAttemptTime = currentTime
        )
      },
      singleUpdate = true
    )
  }

  @Synchronized
  override fun updateJobAfterRetry(id: String, currentTime: Long, runAttempt: Int, nextBackoffInterval: Long, serializedData: ByteArray?) {
    val job = getJobSpec(id)
    if (job == null || !job.isMemoryOnly) {
      jobDatabase.updateJobAfterRetry(id, currentTime, runAttempt, nextBackoffInterval, serializedData)

      // Note: All other fields are accounted for in the min spec. We only need to update from disk if serialized data changes.
      val cached = jobSpecCache[id]
      if (cached != null && !cached.serializedData.contentEquals(serializedData)) {
        jobDatabase.getJobSpec(id)?.let {
          jobSpecCache[id] = it
        }
      }
    }

    updateCachedJobSpecs(
      filter = { it.id == id },
      transformer = { jobSpec ->
        jobSpec.copy(
          isRunning = false,
          lastRunAttemptTime = currentTime,
          nextBackoffInterval = nextBackoffInterval
        )
      },
      singleUpdate = true
    )
  }

  @Synchronized
  override fun updateAllJobsToBePending() {
    jobDatabase.updateAllJobsToBePending()
    // Don't need to update jobSpecCache because all changed fields are in the min spec

    updateCachedJobSpecs(
      filter = { it.isRunning },
      transformer = { jobSpec ->
        jobSpec.copy(
          isRunning = false
        )
      }
    )
  }

  @Synchronized
  override fun updateJobs(jobSpecs: List<JobSpec>) {
    val durable: List<JobSpec> = jobSpecs
      .filter { updatedJob ->
        val found = getJobSpec(updatedJob.id)
        found != null && !found.isMemoryOnly
      }

    if (durable.isNotEmpty()) {
      jobDatabase.updateJobs(durable)
    }

    val updatesById: Map<String, MinimalJobSpec> = jobSpecs
      .map { it.toMinimalJobSpec() }
      .associateBy { it.id }

    updateCachedJobSpecs(
      filter = { updatesById.containsKey(it.id) },
      transformer = { updatesById.getValue(it.id) }
    )

    for (update in jobSpecs) {
      jobSpecCache[update.id] = update
    }
  }

  @Synchronized
  override fun deleteJob(jobId: String) {
    deleteJobs(listOf(jobId))
  }

  @Synchronized
  override fun deleteJobs(jobIds: List<String>) {
    val jobsToDelete: Set<JobSpec> = jobIds
      .mapNotNull { getJobSpec(it) }
      .toSet()

    val durableJobIdsToDelete: List<String> = jobsToDelete
      .filterNot { it.isMemoryOnly }
      .map { it.id }

    val minimalJobsToDelete: Set<MinimalJobSpec> = jobsToDelete
      .map { it.toMinimalJobSpec() }
      .toSet()

    if (durableJobIdsToDelete.isNotEmpty()) {
      jobDatabase.deleteJobs(durableJobIdsToDelete)
    }

    val deleteIds: Set<String> = jobIds.toSet()
    jobs.removeIf { deleteIds.contains(it.id) }
    jobSpecCache.keys.removeAll(deleteIds)
    eligibleJobs.removeAll(minimalJobsToDelete)
    migrationJobs.removeAll(minimalJobsToDelete)

    for (jobId in jobIds) {
      constraintsByJobId.remove(jobId)
      dependenciesByJobId.remove(jobId)

      for (dependencyList in dependenciesByJobId.values) {
        val iter = dependencyList.iterator()

        while (iter.hasNext()) {
          if (iter.next().dependsOnJobId == jobId) {
            iter.remove()
          }
        }
      }
    }
  }

  @Synchronized
  override fun getConstraintSpecs(jobId: String): List<ConstraintSpec> {
    return constraintsByJobId.getOrElse(jobId) { listOf() }
  }

  @Synchronized
  override fun getAllConstraintSpecs(): List<ConstraintSpec> {
    return constraintsByJobId.values.flatten()
  }

  @Synchronized
  override fun getDependencySpecsThatDependOnJob(jobSpecId: String): List<DependencySpec> {
    val all: MutableList<DependencySpec> = mutableListOf()

    var dependencyLayer: List<DependencySpec> = getSingleLayerOfDependencySpecsThatDependOnJob(jobSpecId)

    while (dependencyLayer.isNotEmpty()) {
      all += dependencyLayer

      dependencyLayer = dependencyLayer
        .map { getSingleLayerOfDependencySpecsThatDependOnJob(it.jobId) }
        .flatten()
    }

    return all
  }

  @Synchronized
  override fun getAllDependencySpecs(): List<DependencySpec> {
    return dependenciesByJobId.values.flatten()
  }

  private fun updateCachedJobSpecs(filter: (MinimalJobSpec) -> Boolean, transformer: (MinimalJobSpec) -> MinimalJobSpec, singleUpdate: Boolean = false) {
    val iterator = jobs.listIterator()

    while (iterator.hasNext()) {
      val current = iterator.next()

      if (filter(current)) {
        val updated = transformer(current)
        iterator.set(updated)
        replaceJobInEligibleList(current, updated)

        jobSpecCache.remove(current.id)?.let { currentJobSpec ->
          val updatedJobSpec = currentJobSpec.copy(
            id = updated.id,
            factoryKey = updated.factoryKey,
            queueKey = updated.queueKey,
            createTime = updated.createTime,
            lastRunAttemptTime = updated.lastRunAttemptTime,
            nextBackoffInterval = updated.nextBackoffInterval,
            priority = updated.priority,
            isRunning = updated.isRunning,
            isMemoryOnly = updated.isMemoryOnly
          )
          jobSpecCache[updatedJobSpec.id] = updatedJobSpec
        }

        if (singleUpdate) {
          return
        }
      }
    }
  }

  /**
   * Heart of a lot of the in-memory job management. Will ensure that we have an up-to-date list of eligible jobs in sorted order.
   */
  private fun placeJobInEligibleList(job: MinimalJobSpec) {
    var jobToPlace: MinimalJobSpec? = job

    if (job.queueKey != null) {
      val existingJobInQueue = mostEligibleJobForQueue[job.queueKey]
      if (existingJobInQueue != null) {
        // We only want a single job from each queue. It should be the oldest job with the highest priority.
        if (job.priority > existingJobInQueue.priority || (job.priority == existingJobInQueue.priority && job.createTime < existingJobInQueue.createTime)) {
          mostEligibleJobForQueue[job.queueKey] = job
          eligibleJobs.remove(existingJobInQueue)
        } else {
          // There's a more eligible job in the queue already, so no need to put it in the eligible list
          jobToPlace = null
        }
      }
    }

    if (jobToPlace == null) {
      return
    }

    jobToPlace.queueKey?.let { queueKey ->
      mostEligibleJobForQueue[queueKey] = job
    }

    // At this point, anything queue-related has been handled. We just need to insert this job in the correct spot in the list.
    // Thankfully, we're using a TreeSet, so sorting is automatic.

    eligibleJobs += jobToPlace
  }

  /**
   * Replaces a job in the eligible list with an updated version of the job.
   */
  private fun replaceJobInEligibleList(current: MinimalJobSpec?, updated: MinimalJobSpec?) {
    if (current == null || updated == null) {
      return
    }

    if (updated.queueKey == Job.Parameters.MIGRATION_QUEUE_KEY) {
      migrationJobs.remove(current)
      migrationJobs += updated
    } else {
      eligibleJobs.remove(current)
      current.queueKey?.let { queueKey ->
        if (mostEligibleJobForQueue[queueKey] == current) {
          mostEligibleJobForQueue.remove(queueKey)
        }
      }
      placeJobInEligibleList(updated)
    }
  }

  /**
   * Note that this is currently only checking a specific kind of circular dependency -- ones that are
   * created between dependencies and queues.
   *
   * More specifically, dependencies where one job depends on another job in the same queue that was
   * scheduled *after* it. These dependencies will never resolve. Under normal circumstances these
   * won't occur, but *could* occur if the user changed their clock (either purposefully or automatically).
   *
   * Rather than go through and delete them from the database, removing them from memory at load time
   * serves the same effect and doesn't require new write methods. This should also be very rare.
   */
  private fun DependencySpec.hasCircularDependency(): Boolean {
    val job = getJobSpec(this.jobId)
    val dependsOnJob = getJobSpec(this.dependsOnJobId)

    if (job == null || dependsOnJob == null) {
      return false
    }

    if (job.queueKey == null || dependsOnJob.queueKey == null) {
      return false
    }

    if (job.queueKey != dependsOnJob.queueKey) {
      return false
    }

    return dependsOnJob.createTime > job.createTime
  }

  /**
   * Whether or not the job's eligible to be run based off of it's [Job.nextBackoffInterval] and other properties.
   */
  private fun MinimalJobSpec.hasEligibleRunTime(currentTime: Long): Boolean {
    return this.lastRunAttemptTime > currentTime || (this.lastRunAttemptTime + this.nextBackoffInterval) < currentTime
  }

  private fun getSingleLayerOfDependencySpecsThatDependOnJob(jobSpecId: String): List<DependencySpec> {
    return dependenciesByJobId
      .values
      .flatten()
      .filter { it.dependsOnJobId == jobSpecId }
  }

  /**
   * Converts a [MinimalJobSpec] to a [JobSpec]. We prefer using the cache, but if it's not found, we'll hit the database.
   * We consider this a "recent access" and will cache it for future use.
   */
  private fun MinimalJobSpec.toJobSpec(): JobSpec {
    return jobSpecCache.getOrPut(this.id) {
      jobDatabase.getJobSpec(this.id) ?: throw IllegalArgumentException("JobSpec not found for id: $id")
    }
  }

  private object EligibleJobComparator : Comparator<MinimalJobSpec> {
    override fun compare(o1: MinimalJobSpec, o2: MinimalJobSpec): Int {
      // We want to sort by priority descending, then createTime ascending

      // CAUTION: This is used by a TreeSet, so it must be consistent with equals.
      //          If this compare function says two objects are equal, then only one will be allowed in the set!
      //          This is why the last step is to compare the IDs.
      return when {
        o1.priority > o2.priority -> -1
        o1.priority < o2.priority -> 1
        o1.createTime < o2.createTime -> -1
        o1.createTime > o2.createTime -> 1
        else -> o1.id.compareTo(o2.id)
      }
    }
  }
}

/**
 * Converts a [JobSpec] to a [MinimalJobSpec], which is just a matter of trimming off unnecessary properties.
 */
@VisibleForTesting
fun JobSpec.toMinimalJobSpec(): MinimalJobSpec {
  return MinimalJobSpec(
    id = this.id,
    factoryKey = this.factoryKey,
    queueKey = this.queueKey,
    createTime = this.createTime,
    lastRunAttemptTime = this.lastRunAttemptTime,
    nextBackoffInterval = this.nextBackoffInterval,
    priority = this.priority,
    isRunning = this.isRunning,
    isMemoryOnly = this.isMemoryOnly
  )
}
