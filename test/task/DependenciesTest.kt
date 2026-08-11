package io.kotgent.task

import io.kotgent.core.TaskRef
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DependenciesTest {


    private val a = TaskRef("local:a")
    private val b = TaskRef("local:b")
    private val c = TaskRef("local:c")
    private val d = TaskRef("local:d")
    private val e = TaskRef("local:e")

    private fun graph(vararg edges: Pair<TaskRef, List<TaskRef>>): Map<TaskRef, List<TaskRef>> =
        mapOf(*edges)


    @Test
    fun aSelfEdgeIsACycle() {
        assertTrue(wouldCycle(emptyMap(), a, a), "a task cannot depend on itself")
        assertTrue(wouldCycle(graph(a to listOf(b), b to listOf(c)), a, a))
        assertTrue(wouldCycle(graph(a to listOf(b)), b, b), "a leaf cannot depend on itself either")
    }


    @Test
    fun aDirectBackEdgeIsACycle() {
        assertTrue(wouldCycle(graph(a to listOf(b)), b, a))
    }

    @Test
    fun aTransitiveBackEdgeIsACycle() {
        val edges = graph(a to listOf(b), b to listOf(c))
        assertTrue(wouldCycle(edges, c, a), "A→B→C plus C→A is a ring")
    }

    @Test
    fun aLongChainIsWalkedToItsEnd() {
        val edges = graph(a to listOf(b), b to listOf(c), c to listOf(d), d to listOf(e))
        assertTrue(wouldCycle(edges, e, a), "the far end reaching back to the head")
        assertTrue(wouldCycle(edges, e, b))
        assertTrue(wouldCycle(edges, d, a))
        assertFalse(wouldCycle(edges, a, e), "the chain's own direction is not a ring")
    }

    @Test
    fun aRingReachedThroughABranchIsACycle() {
        val edges = graph(a to listOf(b, c), c to listOf(d), d to listOf(e))
        assertTrue(wouldCycle(edges, e, a))
        assertFalse(wouldCycle(edges, e, b), "B is a leaf; nothing reaches back from it")
    }

    @Test
    fun theTwoDirectionsOfOneProposedEdgeAnswerDifferently() {
        val edges = graph(a to listOf(b))
        assertFalse(wouldCycle(edges, a, b))
        assertTrue(wouldCycle(edges, b, a))
    }


    @Test
    fun aDiamondIsLegal() {
        val edges = graph(b to listOf(d), c to listOf(d), a to listOf(b))
        assertFalse(wouldCycle(edges, a, c), "two paths to one sink is not a cycle")

        val diamond = graph(b to listOf(d), c to listOf(d), a to listOf(b, c))
        assertTrue(wouldCycle(diamond, d, a), "the sink reaching back to the head is a ring")
        assertTrue(wouldCycle(diamond, d, b), "and so is reaching back to either middle")
        assertFalse(wouldCycle(diamond, b, c), "but the two middles may still be ordered")
    }

    @Test
    fun anUnrelatedComponentIsLegal() {
        val edges = graph(a to listOf(b), c to listOf(d))
        assertFalse(wouldCycle(edges, a, c))
        assertFalse(wouldCycle(edges, b, d))
        assertFalse(wouldCycle(edges, d, b))
    }

    @Test
    fun anEmptyGraphRefusesNothingButASelfEdge() {
        assertFalse(wouldCycle(emptyMap(), a, b))
        assertFalse(wouldCycle(emptyMap(), b, a))
    }

    @Test
    fun anUnknownRefIsALeafRatherThanAnError() {
        val edges = graph(a to listOf(b))
        assertFalse(wouldCycle(edges, a, TaskRef("local:ghost")), "an unknown `to` reaches nothing")
        assertFalse(wouldCycle(edges, TaskRef("local:ghost"), a), "an unknown `from` is reached by nothing")
    }

    @Test
    fun reAddingAnEdgeAlreadyInTheGraphIsNotACycle() {
        assertFalse(wouldCycle(graph(a to listOf(b)), a, b))
        assertFalse(wouldCycle(graph(a to listOf(b), b to listOf(c)), b, c))
    }


    @Test
    fun aGraphThatAlreadyContainsARingStillTerminates() {
        val edges = graph(a to listOf(b), b to listOf(c), c to listOf(b))
        assertTrue(wouldCycle(edges, c, a), "A already reaches C, so C depending on A closes a ring")
        assertFalse(wouldCycle(edges, a, c), "nothing reaches A, and the walk must answer, not spin")
        assertFalse(wouldCycle(edges, d, b), "an outsider hanging off the ring is still legal")
    }

    @Test
    fun aDuplicatedOrSelfLoopingEdgeListDoesNotChangeTheAnswer() {
        val edges = graph(a to listOf(b, b, b), b to listOf(b, c))
        assertTrue(wouldCycle(edges, c, a))
        assertFalse(wouldCycle(edges, a, c))
    }

    @Test
    fun aDeepChainIsWalkedIterativelyRatherThanRecursively() {
        val depth = 10_000
        val chain = HashMap<TaskRef, List<TaskRef>>(depth)
        for (i in 0 until depth) {
            chain[TaskRef("local:n$i")] = listOf(TaskRef("local:n${i + 1}"))
        }
        val head = TaskRef("local:n0")
        val tail = TaskRef("local:n$depth")
        assertTrue(wouldCycle(chain, tail, head), "the tail reaching back to the head closes the ring")
        assertFalse(wouldCycle(chain, head, tail), "and the chain's own direction stays legal")
    }
}
