package io.kotgent.task

import io.kotgent.core.TaskRef
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [wouldCycle], the cycle refusal `BacklogDependencies.add` calls before it inserts an edge.
 *
 * One pure function with no storage behind it, so the tests are the specification. What they are built
 * around is the **asymmetry of its two failure modes**:
 *
 *  - A false POSITIVE refuses a legal edge. Annoying, visible, and the operator can see the graph.
 *  - A false NEGATIVE writes a ring into `backlog_deps`, and every task on that ring is then blocked by
 *    another task on it. Nothing on the ring can become `done` first, so `nextCandidate` skips all of it
 *    forever: the cards are still on the board and `task next` just answers "nothing eligible". Nothing
 *    detects it and nothing repairs it short of a human deleting an edge.
 *
 * So the legal shapes get one test each and the cycles get enumerated: direct, transitive, a longer chain,
 * a ring reached only through a shared node, and one whose closing edge points at a node the walk must
 * pass THROUGH rather than start on. The diamond is the counter-example that keeps the walk from being
 * "anything already connected" — two paths to one sink is the shape a real backlog produces most.
 */
class DependenciesTest {

    // --- the vocabulary -------------------------------------------------------------------------

    private val a = TaskRef("local:a")
    private val b = TaskRef("local:b")
    private val c = TaskRef("local:c")
    private val d = TaskRef("local:d")
    private val e = TaskRef("local:e")

    /** `a to listOf(b)` reads "a depends on b" — the direction of `backlog_deps.task_ref → depends_on`. */
    private fun graph(vararg edges: Pair<TaskRef, List<TaskRef>>): Map<TaskRef, List<TaskRef>> =
        mapOf(*edges)

    // --- the degenerate case --------------------------------------------------------------------

    @Test
    fun aSelfEdgeIsACycle() {
        // The self refusal is a degenerate cycle rather than a separate rule, so it must hold whatever
        // the graph around it looks like — including an empty one, where there is nothing to walk.
        assertTrue(wouldCycle(emptyMap(), a, a), "a task cannot depend on itself")
        assertTrue(wouldCycle(graph(a to listOf(b), b to listOf(c)), a, a))
        assertTrue(wouldCycle(graph(a to listOf(b)), b, b), "a leaf cannot depend on itself either")
    }

    // --- cycles -----------------------------------------------------------------------------------

    @Test
    fun aDirectBackEdgeIsACycle() {
        // a depends on b; b depending on a would make the pair mutually blocked.
        assertTrue(wouldCycle(graph(a to listOf(b)), b, a))
    }

    @Test
    fun aTransitiveBackEdgeIsACycle() {
        // A→B→C, and the proposed C→A closes the ring the checklist names. The walk starts at the
        // proposed `to` (A) and has to cross two edges to find C.
        val edges = graph(a to listOf(b), b to listOf(c))
        assertTrue(wouldCycle(edges, c, a), "A→B→C plus C→A is a ring")
    }

    @Test
    fun aLongChainIsWalkedToItsEnd() {
        // The same shape stretched out: the answer must not depend on how far apart the two refs are.
        // A→B→C→D→E, so any of the four can close a ring back onto its own ancestor.
        val edges = graph(a to listOf(b), b to listOf(c), c to listOf(d), d to listOf(e))
        assertTrue(wouldCycle(edges, e, a), "the far end reaching back to the head")
        assertTrue(wouldCycle(edges, e, b))
        assertTrue(wouldCycle(edges, d, a))
        assertFalse(wouldCycle(edges, a, e), "the chain's own direction is not a ring")
    }

    @Test
    fun aRingReachedThroughABranchIsACycle() {
        // The closing edge points at a node the walk reaches only after branching, and only down ONE of
        // the two branches. A depth-1 or single-path check would miss it.
        //   A depends on B and C; C depends on D; D depends on E.
        // Adding "E depends on A" is a ring A→C→D→E→A that the A→B branch says nothing about.
        val edges = graph(a to listOf(b, c), c to listOf(d), d to listOf(e))
        assertTrue(wouldCycle(edges, e, a))
        // ...while the branch that does NOT reach E stays legal.
        assertFalse(wouldCycle(edges, e, b), "B is a leaf; nothing reaches back from it")
    }

    @Test
    fun theTwoDirectionsOfOneProposedEdgeAnswerDifferently() {
        // Direction is the whole rule, so it gets its own assertion rather than being implied. With
        // A→B already in the graph, re-adding A→B is a no-op and B→A is a ring.
        val edges = graph(a to listOf(b))
        assertFalse(wouldCycle(edges, a, b))
        assertTrue(wouldCycle(edges, b, a))
    }

    // --- legal shapes -----------------------------------------------------------------------------

    @Test
    fun aDiamondIsLegal() {
        // The counter-example that keeps the walk from degenerating into "already connected somehow".
        //   B and C both depend on D; A depends on B.
        // Adding "A depends on C" gives A two paths to the same sink — a diamond, not a ring — and it is
        // the shape a real backlog produces most: one piece of groundwork under two follow-ups.
        val edges = graph(b to listOf(d), c to listOf(d), a to listOf(b))
        assertFalse(wouldCycle(edges, a, c), "two paths to one sink is not a cycle")

        // With the diamond closed, the sink still may not point back at its head.
        val diamond = graph(b to listOf(d), c to listOf(d), a to listOf(b, c))
        assertTrue(wouldCycle(diamond, d, a), "the sink reaching back to the head is a ring")
        assertTrue(wouldCycle(diamond, d, b), "and so is reaching back to either middle")
        assertFalse(wouldCycle(diamond, b, c), "but the two middles may still be ordered")
    }

    @Test
    fun anUnrelatedComponentIsLegal() {
        // Two disconnected pieces of work in one project: joining them can never close a ring.
        val edges = graph(a to listOf(b), c to listOf(d))
        assertFalse(wouldCycle(edges, a, c))
        assertFalse(wouldCycle(edges, b, d))
        assertFalse(wouldCycle(edges, d, b))
    }

    @Test
    fun anEmptyGraphRefusesNothingButASelfEdge() {
        // The first dependency in a project: there is nothing to walk, so nothing to refuse.
        assertFalse(wouldCycle(emptyMap(), a, b))
        assertFalse(wouldCycle(emptyMap(), b, a))
    }

    @Test
    fun anUnknownRefIsALeafRatherThanAnError() {
        // A ref absent from the map has no dependencies, so it answers exactly like a leaf. `false` here
        // is therefore NOT proof the ref exists — that question belongs to the store, which asks it
        // separately, and this test is what pins the two apart.
        val edges = graph(a to listOf(b))
        assertFalse(wouldCycle(edges, a, TaskRef("local:ghost")), "an unknown `to` reaches nothing")
        assertFalse(wouldCycle(edges, TaskRef("local:ghost"), a), "an unknown `from` is reached by nothing")
    }

    @Test
    fun reAddingAnEdgeAlreadyInTheGraphIsNotACycle() {
        // `add` treats a duplicate edge as a no-op, so a retry of a request that already landed must not
        // come back as a refusal. Checked on a chain too, where the duplicate sits mid-graph.
        assertFalse(wouldCycle(graph(a to listOf(b)), a, b))
        assertFalse(wouldCycle(graph(a to listOf(b), b to listOf(c)), b, c))
    }

    // --- termination ------------------------------------------------------------------------------

    @Test
    fun aGraphThatAlreadyContainsARingStillTerminates() {
        // Unreachable if this function has guarded every insert — but the caller runs holding the store
        // mutex, and a wedged writer is worse than a redundant visited set. B→C→B is a ring, and both
        // answers below are reached only by walking into it.
        val edges = graph(a to listOf(b), b to listOf(c), c to listOf(b))
        assertTrue(wouldCycle(edges, c, a), "A already reaches C, so C depending on A closes a ring")
        assertFalse(wouldCycle(edges, a, c), "nothing reaches A, and the walk must answer, not spin")
        assertFalse(wouldCycle(edges, d, b), "an outsider hanging off the ring is still legal")
    }

    @Test
    fun aDuplicatedOrSelfLoopingEdgeListDoesNotChangeTheAnswer() {
        // `backlog_deps` has a composite primary key, so neither shape can reach production — but the map
        // is built by a caller, and a repeated ref must cost one visit, not two.
        val edges = graph(a to listOf(b, b, b), b to listOf(b, c))
        assertTrue(wouldCycle(edges, c, a))
        assertFalse(wouldCycle(edges, a, c))
    }

    @Test
    fun aDeepChainIsWalkedIterativelyRatherThanRecursively() {
        // 10_000 links: a recursive ancestor walk overflows the stack here instead of answering, and the
        // failure would be a crashed daemon on somebody's large backlog rather than a wrong answer.
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
