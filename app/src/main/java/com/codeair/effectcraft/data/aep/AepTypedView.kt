package com.codeair.effectcraft.data.aep

/**
 * A typed view over the chunk tree: project items, compositions, layers, the property
 * tree and keyframe lists.
 *
 * Port of `container/typed.rs` from the official EffectCraft project
 * (storytold/effectcraft, MIT OR Apache-2.0). Only facts established from the
 * project's own one-change-per-file corpus and checked by tests against it are
 * decoded. Everything else stays reachable through the raw [AepContainer.Chunk]
 * tree. Views never fail loudly: a chunk without the expected shape yields
 * null or is skipped.
 */
object AepTypedView {

    /** The name a user gave a property or group: the `Utf8` inside its `tdsn` chunk. */
    fun userName(chunk: AepContainer.Chunk): String? {
        val name = chunk.child("tdsn")?.children()?.firstOrNull { it.hasId("Utf8") }
            ?.bytes()?.let { bytesToText(it) } ?: return null
        return if (name.isEmpty() || name == "-_0_/-") null else name
    }

    /** The content of a `Utf8` child as text (not trimmed). */
    fun utf8Child(chunk: AepContainer.Chunk): String? =
        chunk.child("Utf8")?.bytes()?.let { bytesToText(it) }

    /** The match name in a `tdmn` body: ASCII up to the first NUL. */
    fun matchName(body: ByteArray): String = bytesToText(untilNul(body))

    /** The project: the top-level `LIST Fold`, which holds the project items. */
    class Project(val fold: AepContainer.Chunk, val effectDefs: AepContainer.Chunk?) {
        companion object {
            /** The project view of a file, or null when it has no `LIST Fold`. */
            fun of(file: AepContainer.AepFile): Project? =
                file.list("Fold")?.let { Project(it, file.list("EfdG")) }
        }

        /** The top-level items (the project panel's root; folders hold their children). */
        fun items(): List<Item> =
            fold.children().filter { it.isList("Item") }.map { Item(it) }

        /** True when the `LIST Fold` could not be read as chunks (damaged). */
        fun itemsUnreadable(): Boolean = fold.isOpaqueList()

        /** Every item including those inside folders, depth-first, without recursion. */
        fun allItems(): List<Item> {
            val out = ArrayList<Item>()
            val stack = ArrayList(items())
            stack.reverse()
            while (stack.isNotEmpty()) {
                // removeAt(lastIndex), NOT removeLast(): ArrayList.removeLast()
                // is a Java 21 SequencedCollection member — it compiles on a
                // JDK 21 host but throws NoSuchMethodError on the CI JDK 17
                // (and on Android). removeAt is API 1 and JDK 1.2.
                val item = stack.removeAt(stack.lastIndex)
                val kids = ArrayList(item.children())
                kids.reverse()
                out.add(item)
                stack.addAll(kids)
            }
            return out
        }
    }

    /** What a project item is (the first 16-bit word of its `idta` chunk). */
    sealed class ItemKind {
        /** `1` */
        object Folder : ItemKind()

        /** `4` */
        object Composition : ItemKind()

        /** `7` */
        object Footage : ItemKind()

        /** Any other value. */
        class Other(val code: Int) : ItemKind()

        override fun equals(other: Any?): Boolean = when {
            this === other -> true
            this is Folder && other is Folder -> true
            this is Composition && other is Composition -> true
            this is Footage && other is Footage -> true
            this is Other && other is Other -> code == other.code
            else -> false
        }

        override fun hashCode(): Int = when (this) {
            is Folder -> 1
            is Composition -> 4
            is Footage -> 7
            is Other -> code
        }
    }

    /** A project item (`LIST Item`). */
    class Item(val chunk: AepContainer.Chunk) {
        /** The item kind, from `idta`. */
        fun kind(): ItemKind? {
            val idta = chunk.child("idta")?.bytes() ?: return null
            return when (val code = beU16(idta, 0)) {
                null -> null
                1 -> ItemKind.Folder
                4 -> ItemKind.Composition
                7 -> ItemKind.Footage
                else -> ItemKind.Other(code)
            }
        }

        /** The item name (the `Utf8` child). Null when absent or empty. */
        fun name(): String? = utf8Child(chunk).takeIf { !it.isNullOrEmpty() }

        /** The items inside a folder (`LIST Sfdr`). */
        fun children(): List<Item> =
            chunk.childList("Sfdr")?.children().orEmpty()
                .filter { it.isList("Item") }.map { Item(it) }

        /** Whether the item has the `LIST Sfdr` of a folder's contents. */
        fun hasFolderContents(): Boolean = chunk.childList("Sfdr") != null

        /** True when the folder's `LIST Sfdr` is there but could not be read as chunks. */
        fun folderContentsUnreadable(): Boolean =
            chunk.childList("Sfdr")?.isOpaqueList() ?: false

        /** The composition view when this item has composition data (`cdta`). */
        fun comp(): Comp? =
            if (chunk.child("cdta") != null) Comp(chunk) else null
    }

    /** The time base of a composition: one frame lasts [frameDuration] units and a second has [timescale] units. */
    class TimeBase(val frameDuration: Long, val timescale: Long) {
        /** Frames per second (`timescale / frame_duration`), null for a zero duration. */
        fun frameRate(): Double? =
            if (frameDuration != 0L) timescale.toDouble() / frameDuration.toDouble() else null

        /** Converts time units to seconds, null for a zero timescale. */
        fun seconds(units: Int): Double? =
            if (timescale != 0L) units.toDouble() / timescale.toDouble() else null

        override fun equals(other: Any?): Boolean =
            other is TimeBase && frameDuration == other.frameDuration && timescale == other.timescale

        override fun hashCode(): Int = 31 * frameDuration.hashCode() + timescale.hashCode()
    }

    /** A composition item (an [Item] with `cdta`). */
    class Comp(val item: AepContainer.Chunk) {
        /** The time base from `cdta` (`u32` at byte 4 is the frame duration, `u32` at byte 8 the timescale). */
        fun timeBase(): TimeBase? {
            val cdta = item.child("cdta")?.bytes() ?: return null
            val frameDuration = beU32(cdta, 4) ?: return null
            val timescale = beU32(cdta, 8) ?: return null
            return TimeBase(frameDuration, timescale)
        }

        /** The layers, top layer first (`LIST Layr`). */
        fun layers(): List<Layer> =
            item.children().filter { it.isList("Layr") }.map { Layer(it) }
    }

    /** A layer (`LIST Layr`). */
    class Layer(val chunk: AepContainer.Chunk) {
        /** The layer name when it was set; null when empty (then it shows its source's name). */
        fun name(): String? = utf8Child(chunk).takeIf { !it.isNullOrEmpty() }

        /** The 3D layer switch: bit `0x04` of byte 38 of `ldta`. */
        fun isThreeD(): Boolean? {
            val ldta = chunk.child("ldta")?.bytes() ?: return null
            val b = ldta.getOrNull(38) ?: return null
            return b.toInt() and 0x04 != 0
        }

        /** The root of the layer's property tree. */
        fun properties(): PropGroup? =
            chunk.childList("tdgp")?.let { PropGroup(it) }

        /** The Orientation of a 3D layer (values.rs:185): a `LIST otst` holding a `tdbs`
         * and a `LIST otky` whose `otda` is three big-endian f64 degrees [x, y, z];
         * null when absent (the default [0, 0, 0]) or unreadable. */
        fun orientation(): Orientation? {
            val transform = (properties()?.get("ADBE Transform Group") as? PropNode.Group)?.group
                ?: return null
            val otst = (transform.get("ADBE Orientation") as? PropNode.Other)?.chunk ?: return null
            if (!otst.isList("otst")) return null
            val property = otst.childList("tdbs")?.let { Property.fromChunk(it) }
            val otda = otst.childList("otky")?.child("otda")?.bytes() ?: return null
            if (otda.size < 24) return null
            val degrees = DoubleArray(3) { i ->
                java.nio.ByteBuffer.wrap(otda, i * 8, 8).double
            }
            return Orientation(
                degrees = degrees,
                animated = property?.isAnimated() == true,
                hasExpression = property?.expression() != null
            )
        }
    }

    /** The Orientation of a 3D layer (`ADBE Orientation`, 488). */
    data class Orientation(
        /** Degrees [x, y, z]: the static value. */
        val degrees: DoubleArray,
        /** The `tdbs` of the `otst` has keys. */
        val animated: Boolean,
        /** The `tdbs` of the `otst` has an expression. */
        val hasExpression: Boolean
    )

    /** A node of the property tree. */
    sealed class PropNode {
        /** A property group (`LIST tdgp`). */
        class Group(val group: PropGroup) : PropNode()

        /** A property (`LIST tdbs`). */
        class Property(val property: AepTypedView.Property) : PropNode()

        /** Any other list that follows a match name (observed: `otst`, the effect instance `sspc`). */
        class Other(val chunk: AepContainer.Chunk) : PropNode()

        companion object {
            /** The node a `LIST` after a match name is. */
            fun of(chunk: AepContainer.Chunk): PropNode = when {
                chunk.isList("tdgp") -> Group(PropGroup(chunk))
                chunk.isList("tdbs") -> Property(AepTypedView.Property(chunk))
                else -> Other(chunk)
            }
        }
    }

    /** A match name with the node it names. */
    class PropEntry(val matchName: String, val node: PropNode)

    /** A property group (`LIST tdgp`): after its `tdsb` and `tdsn` chunks come pairs of a `tdmn`
     * (the 40-byte NUL-padded ASCII match name) and the list it names; a `tdmn` named
     * `ADBE Group End` closes the group and names nothing. */
    class PropGroup(val chunk: AepContainer.Chunk) {
        companion object {
            /** A group view of a `LIST tdgp` chunk. */
            fun fromChunk(chunk: AepContainer.Chunk): PropGroup? =
                if (chunk.isList("tdgp")) PropGroup(chunk) else null
        }

        /** The name the user gave the group (e.g. `Mask 1`, `Group 1`); null when it has none. */
        fun userName(): String? = userName(chunk)

        /** The group's flags (`tdsb`, a `u32`). */
        fun flags(): Long? {
            val bytes = chunk.child("tdsb")?.bytes() ?: return null
            return beU32(bytes, 0)
        }

        /** The entries in file order. */
        fun entries(): List<PropEntry> {
            val out = ArrayList<PropEntry>()
            var pending: String? = null
            for (c in chunk.children()) {
                if (c.hasId("tdmn")) {
                    pending = c.bytes()?.let { matchName(it) }
                } else if (c.id == AepContainer.LIST && pending != null) {
                    out.add(PropEntry(pending, PropNode.of(c)))
                    pending = null
                }
            }
            return out
        }

        /** The match names of the entries, in file order. */
        fun names(): List<String> = entries().map { it.matchName }

        /** The first entry with this match name. One pass that stops at it. */
        fun get(name: String): PropNode? {
            var pending: ByteArray? = null
            for (c in chunk.children()) {
                if (c.hasId("tdmn")) {
                    pending = c.bytes()
                } else if (c.id == AepContainer.LIST && pending != null && matchName(pending) == name) {
                    return PropNode.of(c)
                }
            }
            return null
        }

        /** Follows a path of match names through nested groups. */
        fun find(path: List<String>): PropNode? {
            val first = path.firstOrNull() ?: return null
            val node = get(first) ?: return null
            val rest = path.drop(1)
            if (rest.isEmpty()) return node
            return when (node) {
                is PropNode.Group -> node.group.find(rest)
                else -> null
            }
        }
    }

    /** A property (`LIST tdbs`): `tdsb`, `tdsn`, the 124-byte header `tdb4`, then either the static
     * value `cdat` or a keyframe `LIST list`. */
    class Property(val chunk: AepContainer.Chunk) {
        companion object {
            /** A property view of a `LIST tdbs` chunk. */
            fun fromChunk(chunk: AepContainer.Chunk): Property? =
                if (chunk.isList("tdbs")) Property(chunk) else null
        }

        /** The name the user gave the property; null when it has none. */
        fun userName(): String? = userName(chunk)

        /** The raw 124-byte `tdb4` header. */
        fun header(): ByteArray? = chunk.child("tdb4")?.bytes()

        /** The number of value components: byte 3 of `tdb4`. */
        fun dims(): Int? = header()?.getOrNull(3)?.toInt()?.and(0xFF)

        /** True when the property has keyframes (its value is a `LIST list` instead of `cdat`). */
        fun isAnimated(): Boolean = chunk.childList("list") != null

        /** The static value: the first `dims` big-endian `f64` of `cdat`. */
        fun staticValue(): List<Double>? {
            val dims = dims() ?: return null
            val cdat = chunk.child("cdat")?.bytes() ?: return null
            val out = ArrayList<Double>(dims)
            for (i in 0 until dims) {
                out.add(beF64(cdat, i * 8) ?: return null)
            }
            return out
        }

        /** The keyframe list of an animated property, with the record layout the property header selects. */
        fun keys(): KeyList? {
            val list = KeyList.fromChunk(chunk.childList("list") ?: return null) ?: return null
            val header = header()
            val size = list.header.recordSize
            val layout = header?.let { h ->
                val components = h.getOrNull(3)?.toInt()?.and(0xFF) ?: return@let null
                val n = components.toLong()
                val spatialSize = 56 + 24 * n
                val plainSize = 8 + 40 * n
                val spatial = when {
                    size == spatialSize && size != plainSize -> true
                    size == plainSize && size != spatialSize -> false
                    else -> (h.getOrNull(7)?.toInt()?.and(0xFF) ?: return@let null) != 0
                }
                KeyLayout(components, spatial)
            }
            return KeyList(list.header, list.ldat, layout)
        }
    }

    /** The header `lhd3` of a `LIST list`: 13 big-endian `u32`. */
    class ListHeader(val magic: Long, val count: Long, val recordSize: Long, val words: LongArray) {
        companion object {
            /** Parses a 52-byte `lhd3` body. */
            fun parse(body: ByteArray): ListHeader? {
                if (body.size != 52) return null
                val words = LongArray(13)
                for (i in words.indices) {
                    words[i] = beU32(body, i * 4) ?: return null
                }
                return ListHeader(words[0], words[2], words[4], words)
            }
        }

        override fun equals(other: Any?): Boolean =
            other is ListHeader && magic == other.magic && count == other.count &&
                recordSize == other.recordSize && words.contentEquals(other.words)

        override fun hashCode(): Int {
            var result = magic.hashCode()
            result = 31 * result + count.hashCode()
            result = 31 * result + recordSize.hashCode()
            result = 31 * result + words.contentHashCode()
            return result
        }
    }

    /** A `LIST list`: `lhd3` and `ldat`, where `len(ldat) == count * record_size`. */
    class KeyList(val header: ListHeader, val ldat: ByteArray, val layout: KeyLayout?) {
        companion object {
            /** A view of a `LIST list` chunk. Null when `lhd3` is not 52 bytes or `ldat` is not
             * exactly `count * record_size` bytes. */
            fun fromChunk(chunk: AepContainer.Chunk): KeyList? {
                if (!chunk.isList("list")) return null
                val header = ListHeader.parse(chunk.child("lhd3")?.bytes() ?: return null) ?: return null
                val ldat = chunk.child("ldat")?.bytes() ?: byteArrayOf()
                val expected = checkedMul(header.count, header.recordSize) ?: return null
                if (ldat.size.toLong() != expected) return null
                return KeyList(header, ldat, null)
            }

            private fun checkedMul(a: Long, b: Long): Long? {
                if (a == 0L || b == 0L) return 0L
                if (a > Long.MAX_VALUE / b) return null
                return a * b
            }
        }

        /** The number of records actually present. Not the declared count. */
        fun len(): Int = records().size

        /** True when there are no records. */
        fun isEmpty(): Boolean = ldat.isEmpty()

        /** The records as byte slices. */
        fun records(): List<ByteArray> {
            val size = header.recordSize.coerceAtLeast(1L)
            if (size > Int.MAX_VALUE) return emptyList()
            val chunkSize = size.toInt()
            val count = ldat.size / chunkSize
            val out = ArrayList<ByteArray>(count)
            for (i in 0 until count) {
                out.add(ldat.copyOfRange(i * chunkSize, (i + 1) * chunkSize))
            }
            return out
        }

        /** The record layout from the property header, null for a list that does not belong to a property. */
        fun layout(): KeyLayout? = layout

        /** The records of a keyframe list as [Keyframe]s. */
        fun keyframes(): List<Keyframe> = records().map { Keyframe(it, layout) }
    }

    /** What the property header says about its keyframe records. */
    class KeyLayout(val components: Int, val spatial: Boolean) {
        override fun equals(other: Any?): Boolean =
            other is KeyLayout && components == other.components && spatial == other.spatial

        override fun hashCode(): Int = 31 * components + spatial.hashCode()
    }

    /** One keyframe record: the time is the `i32` at byte 0, in the composition's time units. */
    class Keyframe(val record: ByteArray, val layout: KeyLayout?) {
        /** The whole record. */
        fun raw(): ByteArray = record

        /** The keyframe time in time units (layer time). */
        fun timeUnits(): Int? = beU32(record, 0)?.toInt()
    }
}
