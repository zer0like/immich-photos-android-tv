package com.immichclient.tv

import kotlin.random.Random

/**
 * Non-repeating random order: shuffled once per round so an item only repeats after all others have been shown.
 * When a new round starts, the currently shown item is immediately marked seen to prevent back-to-back repeats.
 */
class Shuffler(private val random: Random = Random.Default) {
    private var order = IntArray(0)
    private var seen = BooleanArray(0)
    private var pos = 0

    private fun newRound(n: Int) {
        order = IntArray(n) { it }.also { it.shuffle(random) }
        seen = BooleanArray(n)
        pos = 0
    }

    /** Next item after [current] satisfying [allowed]; does not mark it yet ([mark] does that). -1 = none available. */
    fun next(n: Int, current: Int, allowed: (Int) -> Boolean): Int {
        if (n <= 1) return -1
        if (order.size != n) newRound(n)
        mark(current)
        for (round in 0..1) {
            for (p in pos until n) {
                val i = order[p]
                if (!seen[i] && i != current && allowed(i)) return i
            }
            if (round == 0) { newRound(n); mark(current) }
        }
        return -1
    }

    /** Marks index [i] as shown. */
    fun mark(i: Int) {
        if (i !in seen.indices) return
        seen[i] = true
        while (pos < order.size && seen[order[pos]]) pos++
    }
}
