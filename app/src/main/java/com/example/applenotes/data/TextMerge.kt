package com.example.applenotes.data

/**
 * Line-level three-way merge (diff3) for resolving a save CONFLICT (issue #4).
 *
 * Inputs are the note's visible text as the user started editing it
 * ([base]), the user's draft ([local]) and the server's current text
 * ([remote]). Both sides are diffed against base; every base→local hunk is
 * applied onto remote unless it collides with a base→remote hunk.
 *
 * Granularity: tokens are lines *including* their trailing '\n' (the last
 * line may lack one). So two devices editing the same paragraph is a
 * conflict, while edits to different paragraphs, including neighbouring
 * ones, merge. Deliberately conservative: when in doubt, report a conflict
 * and let the user choose, rather than guess.
 *
 * Collision rule for a local hunk L and a remote hunk R (base token ranges):
 * - the ranges share at least one base token → conflict;
 * - both are pure insertions at the same point → conflict (which goes
 *   first is a guess);
 * - one is a pure insertion strictly inside the other's range → conflict;
 * - identical hunks (same range, same replacement) are not a conflict;
 *   the change is taken once;
 * - anything else (including an insertion right at the edge of the other
 *   side's range, or edits to adjacent lines) merges in base order;
 * - except: a line without its trailing '\n' (one side dropped the base's
 *   final newline) followed by anything else in the merge → conflict, since
 *   applying both would glue two lines into one.
 *
 * Pure Kotlin on UTF-16 strings; no Android dependencies. Lines are compared
 * whole, so surrogate pairs, CJK and U+FFFC attachment markers need no
 * special handling: a token is never split.
 */
object TextMerge {

    sealed interface Result

    /**
     * A clean merge. [text] is the merged note. For every char of [text],
     * [localIndex] / [remoteIndex] / [baseIndex] give the index of the same
     * char in that input, or -1 when it doesn't come from there (a char
     * from a local-only change has only a local index, etc.).
     *
     * [steps] rebuilds [text] from remote one local change at a time: each
     * entry is remote with the first k local hunks applied, the last entry
     * is [text]; empty when [text] == remote. Splicing the proto through
     * these one by one keeps the splice's single prefix/suffix diff from
     * re-inserting (and re-attributing to us) remote chars that sit between
     * two local edits.
     */
    class Clean(
        val text: String,
        val baseIndex: IntArray,
        val localIndex: IntArray,
        val remoteIndex: IntArray,
        val steps: List<String>,
    ) : Result

    /** Local and remote edits overlap; nothing is merged. */
    data object Conflict : Result

    /** A base range [baseStart, baseEnd) replaced by side tokens [sideStart, sideEnd). */
    internal data class Hunk(val baseStart: Int, val baseEnd: Int, val sideStart: Int, val sideEnd: Int) {
        val isInsertion get() = baseStart == baseEnd
    }

    /** Above this many DP cells the middle of a diff becomes one hunk (more conflicts, never wrong). */
    private const val MAX_DP_CELLS = 4_000_000L

    fun merge(base: String, local: String, remote: String): Result {
        val b = tokenize(base)
        val l = tokenize(local)
        val r = tokenize(remote)
        val lh = diff(b, l)
        val rh = diff(b, r)

        // Collision check, and pairing of identical hunks.
        val identical = HashMap<Int, Int>() // local hunk idx -> remote hunk idx
        for ((li, x) in lh.withIndex()) {
            for ((ri, y) in rh.withIndex()) {
                if (!collide(x, y)) continue
                if (x.baseStart == y.baseStart && x.baseEnd == y.baseEnd &&
                    l.subList(x.sideStart, x.sideEnd) == r.subList(y.sideStart, y.sideEnd)
                ) {
                    identical[li] = ri
                } else {
                    return Conflict
                }
            }
        }

        // Every hunk applies. Walk them in base order.
        // pairedWith: for a local hunk with an identical remote hunk, that remote hunk.
        class Applied(val h: Hunk, val local: Boolean, val pairedWith: Hunk?)
        val pairedRemote = identical.values.toSet()
        val applied = buildList {
            lh.forEachIndexed { i, h -> add(Applied(h, local = true, pairedWith = identical[i]?.let { rh[it] })) }
            rh.forEachIndexed { i, h -> if (i !in pairedRemote) add(Applied(h, local = false, pairedWith = null)) }
        }.sortedWith(compareBy({ it.h.baseStart }, { it.h.baseEnd }))

        val bOff = offsets(b)
        val lOff = offsets(l)
        val rOff = offsets(r)
        val out = StringBuilder()
        val bi = IntArrayBuilder()
        val li = IntArrayBuilder()
        val ri = IntArrayBuilder()
        // Where each local-only hunk ends in the merged text, and where remote resumes.
        val stepCuts = ArrayList<Pair<Int, Int>>()

        var pos = 0
        var emittedTokens = 0
        var shiftL = 0
        var shiftR = 0
        fun emitStable(until: Int) {
            for (t in pos until until) {
                val tok = b[t]
                emittedTokens++
                for (c in tok.indices) {
                    out.append(tok[c])
                    bi.add(bOff[t] + c)
                    li.add(lOff[t + shiftL] + c)
                    ri.add(rOff[t + shiftR] + c)
                }
            }
            pos = until
        }

        for (a in applied) {
            val h = a.h
            emitStable(h.baseStart)
            val baseLen = h.baseEnd - h.baseStart
            if (a.local) {
                // Local tokens (for an identical pair, remote has the same ones).
                val remoteStart = a.pairedWith?.sideStart ?: -1
                for ((k, t) in (h.sideStart until h.sideEnd).withIndex()) {
                    val tok = l[t]
                    emittedTokens++
                    for (c in tok.indices) {
                        out.append(tok[c])
                        bi.add(-1)
                        li.add(lOff[t] + c)
                        ri.add(if (remoteStart >= 0) rOff[remoteStart + k] + c else -1)
                    }
                }
                shiftL += (h.sideEnd - h.sideStart) - baseLen
                if (a.pairedWith != null) {
                    shiftR += (h.sideEnd - h.sideStart) - baseLen
                } else {
                    // Remote resumes at base token h.baseEnd.
                    stepCuts += out.length to rOff[h.baseEnd + shiftR]
                }
            } else {
                for (t in h.sideStart until h.sideEnd) {
                    val tok = r[t]
                    emittedTokens++
                    for (c in tok.indices) {
                        out.append(tok[c])
                        bi.add(-1)
                        li.add(-1)
                        ri.add(rOff[t] + c)
                    }
                }
                shiftR += (h.sideEnd - h.sideStart) - baseLen
            }
            pos = h.baseEnd
        }
        emitStable(b.size)

        val text = out.toString()
        // Only the very last line may lack its '\n'. One side dropping the
        // base's final newline while the other appends after it would join
        // two lines ("a\nB" + "c" → "a\nBc"): refuse rather than guess.
        if (tokenize(text).size != emittedTokens) return Conflict
        val steps = if (text == remote) {
            emptyList()
        } else {
            stepCuts.map { (mergedEnd, remoteResume) -> text.substring(0, mergedEnd) + remote.substring(remoteResume) }
                .let { s -> if (s.lastOrNull() == text) s else s + text }
        }
        return Clean(text, bi.toArray(), li.toArray(), ri.toArray(), steps)
    }

    private fun collide(x: Hunk, y: Hunk): Boolean = when {
        x.isInsertion && y.isInsertion -> x.baseStart == y.baseStart
        x.isInsertion -> x.baseStart > y.baseStart && x.baseStart < y.baseEnd
        y.isInsertion -> y.baseStart > x.baseStart && y.baseStart < x.baseEnd
        else -> maxOf(x.baseStart, y.baseStart) < minOf(x.baseEnd, y.baseEnd)
    }

    /** Lines including their '\n'. "" → [], "a\nb" → ["a\n", "b"], "a\n" → ["a\n"]. */
    internal fun tokenize(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var start = 0
        while (start < s.length) {
            val nl = s.indexOf('\n', start)
            val end = if (nl < 0) s.length else nl + 1
            out.add(s.substring(start, end))
            start = end
        }
        return out
    }

    /** Char offset of each token, plus the total length at index size. */
    private fun offsets(tokens: List<String>): IntArray {
        val o = IntArray(tokens.size + 1)
        for (i in tokens.indices) o[i + 1] = o[i] + tokens[i].length
        return o
    }

    /** Minimal LCS diff of [a] → [b] as hunks of consecutive non-matching tokens. */
    internal fun diff(a: List<String>, b: List<String>): List<Hunk> {
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val n = a.size - pre - suf
        val m = b.size - pre - suf
        if (n == 0 && m == 0) return emptyList()
        if (n == 0 || m == 0 || n.toLong() * m > MAX_DP_CELLS) {
            return listOf(Hunk(pre, pre + n, pre, pre + m))
        }
        // lcs[i][j] = LCS length of a[pre+i ..] and b[pre+j ..] (middle only).
        val w = m + 1
        val lcs = IntArray((n + 1) * w)
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i * w + j] = if (a[pre + i] == b[pre + j]) {
                    lcs[(i + 1) * w + j + 1] + 1
                } else {
                    maxOf(lcs[(i + 1) * w + j], lcs[i * w + j + 1])
                }
            }
        }
        val hunks = ArrayList<Hunk>()
        var i = 0
        var j = 0
        var hs = -1
        var hsb = -1
        fun close() {
            if (hs >= 0) {
                hunks.add(Hunk(pre + hs, pre + i, pre + hsb, pre + j))
                hs = -1
            }
        }
        while (i < n || j < m) {
            if (i < n && j < m && a[pre + i] == b[pre + j] && lcs[i * w + j] == lcs[(i + 1) * w + j + 1] + 1) {
                close()
                i++
                j++
            } else {
                if (hs < 0) {
                    hs = i
                    hsb = j
                }
                if (j >= m || (i < n && lcs[(i + 1) * w + j] >= lcs[i * w + j + 1])) i++ else j++
            }
        }
        close()
        return hunks
    }

    private class IntArrayBuilder {
        private var a = IntArray(64)
        private var n = 0
        fun add(v: Int) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun toArray(): IntArray = a.copyOf(n)
    }
}
