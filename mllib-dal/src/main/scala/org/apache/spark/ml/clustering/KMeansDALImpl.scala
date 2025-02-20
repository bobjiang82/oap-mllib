/*
 * Copyright 2020 Intel Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.ml.clustering

import org.apache.spark.internal.Logging
import org.apache.spark.ml.linalg.Vector
import org.apache.spark.ml.util._
import org.apache.spark.mllib.clustering.{KMeansModel => MLlibKMeansModel}
import org.apache.spark.mllib.linalg.{Vector => OldVector, Vectors => OldVectors}
import org.apache.spark.rdd.RDD

class KMeansDALImpl(var nClusters: Int,
                    var maxIterations: Int,
                    var tolerance: Double,
                    val distanceMeasure: String,
                    val centers: Array[OldVector],
                    val executorNum: Int,
                    val executorCores: Int
                   ) extends Serializable with Logging {

  def train(data: RDD[Vector], instr: Option[Instrumentation]): MLlibKMeansModel = {

    val coalescedTables = OneDAL.vectorsToMergedNumericTables(data, executorNum)

    val executorIPAddress = Utils.sparkFirstExecutorIP(coalescedTables.sparkContext)
    val kvsIP = coalescedTables.sparkContext.conf.get("spark.oap.mllib.oneccl.kvs.ip",
      executorIPAddress)
    val kvsPortDetected = Utils.checkExecutorAvailPort(coalescedTables, kvsIP)
    val kvsPort = coalescedTables.sparkContext.conf.getInt("spark.oap.mllib.oneccl.kvs.port",
      kvsPortDetected)

    val kvsIPPort = kvsIP + "_" + kvsPort

    val results = coalescedTables.mapPartitionsWithIndex { (rank, table) =>
      val tableArr = table.next()
      OneCCL.init(executorNum, rank, kvsIPPort)

      val initCentroids = OneDAL.makeNumericTable(centers)
      val result = new KMeansResult()
      val cCentroids = cKMeansDALComputeWithInitCenters(
        tableArr,
        initCentroids.getCNumericTable,
        nClusters,
        tolerance,
        maxIterations,
        executorNum,
        executorCores,
        result
      )

      val ret = if (OneCCL.isRoot()) {
        assert(cCentroids != 0)
        val centerVectors = OneDAL.numericTableToVectors(OneDAL.makeNumericTable(cCentroids))
        Iterator((centerVectors, result.totalCost, result.iterationNum))
      } else {
        Iterator.empty
      }

      OneCCL.cleanup()

      ret
    }.collect()

    // Make sure there is only one result from rank 0
    assert(results.length == 1)

    // Release native memory for numeric tables
    OneDAL.releaseNumericTables(data.sparkContext)

    val centerVectors = results(0)._1
    val totalCost = results(0)._2
    val iterationNum = results(0)._3

    if (iterationNum == maxIterations) {
      logInfo(s"KMeans reached the max number of iterations: $maxIterations.")
    } else {
      logInfo(s"KMeans converged in $iterationNum iterations.")
    }

    logInfo(s"The cost is $totalCost.")
    instr.foreach(_.logInfo(s"OneDAL output centroids:\n${centerVectors.mkString("\n")}"))

    val parentModel = new MLlibKMeansModel(
      centerVectors.map(OldVectors.fromML(_)),
      distanceMeasure, totalCost, iterationNum)

    parentModel
  }

  // Single entry to call KMeans DAL backend with initial centers, output centers
  @native private def cKMeansDALComputeWithInitCenters(data: Long, centers: Long,
                                                       cluster_num: Int,
                                                       tolerance: Double,
                                                       iteration_num: Int,
                                                       executor_num: Int,
                                                       executor_cores: Int,
                                                       result: KMeansResult): Long

}
