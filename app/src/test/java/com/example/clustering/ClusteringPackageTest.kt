package com.example.clustering

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusteringPackageTest {

    private fun buildSyntheticClusters(pointsPerCluster: Int): List<ClusterPoint> {
        val centers = listOf(
            0.20 to 0.20,
            0.80 to 0.20,
            0.50 to 0.80
        )
        val list = mutableListOf<ClusterPoint>()
        var id = 1
        for ((cx, cy) in centers) {
            for (i in 0 until pointsPerCluster) {
                val offset = (i % 5 - 2) * 0.01
                list.add(ClusterPoint(id = id++, x = cx + offset, y = cy - offset))
            }
        }
        return list
    }

    @Test
    fun `KMeansClusterer runs synchronously on calling thread for small dataset`() {
        val smallPoints = buildSyntheticClusters(pointsPerCluster = 15) // 45 points
        val clusterer = KMeansClusterer()
        val result = clusterer.fit(
            points = smallPoints,
            config = KMeansParameters(k = 3, useKMeansPlusPlus = true, randomSeed = 7L)
        )

        assertFalse(result.executedAsync)
        assertTrue(result.finalSnapshot.isConverged)
        assertEquals(3, result.finalSnapshot.centroids.size)
        assertEquals(45, result.finalSnapshot.assignments.size)
        assertTrue(result.finalSnapshot.silhouetteScore > 0.5)
    }

    @Test
    fun `DbscanClusterer runs synchronously and identifies core, border, and noise points`() {
        val points = buildSyntheticClusters(pointsPerCluster = 12).toMutableList()
        // Add isolated outlier/noise point far from clusters
        points.add(ClusterPoint(id = 999, x = 0.01, y = 0.99))

        val dbscan = DbscanClusterer()
        val result = dbscan.fit(
            points = points,
            config = DbscanParameters(epsilon = 0.06, minPoints = 4)
        )

        assertFalse(result.executedAsync)
        assertTrue(result.finalSnapshot.isConverged)
        assertEquals(3, result.finalSnapshot.clusterStats.size)
        assertEquals(1, result.finalSnapshot.noisePointCount)

        val outlierAssignment = result.finalSnapshot.assignments.first { it.point.id == 999 }
        assertEquals(-1, outlierAssignment.clusterId)
        assertEquals(ClusterNodeRole.NOISE, outlierAssignment.role)
    }

    @Test
    fun `ClusteringRunner adaptively executes small dataset inline and large dataset in coroutine`() = runTest {
        val runner = ClusteringRunner(
            smallDatasetThreshold = 60,
            largeDatasetDispatcher = Dispatchers.Default
        )

        val smallDataset = buildSyntheticClusters(pointsPerCluster = 15) // 45 <= 60
        val smallKMeans = runner.runKMeansAdaptive(smallDataset, KMeansParameters(k = 3))
        assertFalse("Small dataset should execute inline without async dispatch", smallKMeans.executedAsync)
        assertTrue(smallKMeans.finalSnapshot.isConverged)

        val largeDataset = buildSyntheticClusters(pointsPerCluster = 40) // 120 > 60
        val largeKMeans = runner.runKMeansAdaptive(largeDataset, KMeansParameters(k = 3))
        assertTrue("Large dataset should offload to coroutine dispatcher", largeKMeans.executedAsync)
        assertTrue(largeKMeans.finalSnapshot.isConverged)

        val largeDbscan = runner.runDbscanAdaptive(
            largeDataset,
            DbscanParameters(epsilon = 0.06, minPoints = 5)
        )
        assertTrue("Large DBSCAN dataset should offload to coroutine dispatcher", largeDbscan.executedAsync)
        assertEquals(3, largeDbscan.finalSnapshot.clusterStats.size)
    }
}
