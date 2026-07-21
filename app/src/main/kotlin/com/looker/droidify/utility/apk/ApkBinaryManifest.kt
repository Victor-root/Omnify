package com.looker.droidify.utility.apk

/**
 * Parses an Android compiled (binary XML) `AndroidManifest.xml` far enough to list every `<service>`
 * and `<receiver>` component it declares (each with its class name and the intent actions its nested
 * `<intent-filter>`s bind it to — see [components]), to read its `<uses-sdk>` element's
 * `minSdkVersion`/`targetSdkVersion` (see [usesSdk]) — the same min/target shown for a catalogue release
 * ([com.looker.droidify.compose.appDetail.components.PackageItem]) — and to list its declared
 * `<uses-permission>`/`<uses-feature>` names (see [permissionsAndFeatures]), feeding the same
 * Google-services detection a catalogue app's F-Droid-index metadata does
 * ([com.looker.droidify.compose.appDetail.detectGoogleServicesDependencies]), and to read the
 * `<application>` element's `icon` resource reference (see [applicationIconResourceId]), the starting
 * point for [RemoteApkIconReader]'s remote icon extraction. All read here for a tracked external
 * source's release (or a catalogue release with no icon of its own), neither of which carries such
 * metadata already resolved.
 *
 * Why component-level parsing instead of just searching the manifest's string pool for a marker
 * string: a permission or intent-action string being *present* says nothing about WHOSE component
 * uses it. Confirmed on a real APK (a de-Googled Signal fork): the maintainer's patch removes every
 * one of the app's own push services from the manifest, yet the merged manifest still declares the
 * bundled Firebase library's own generic components (`FirebaseInstanceIdReceiver`,
 * `FirebaseMessagingService`) — Android's manifest merger folds every library's manifest into the
 * app's, and a source patch to the app's manifest doesn't touch those. So the push action strings are
 * all still there, while every component that would let the *app's own code* receive a push is gone.
 * Only knowing which class each action is bound to can tell those two situations apart.
 *
 * Binary XML shares its chunk + string-pool format with `resources.arsc` (see [ApkResourceLocales],
 * whose string-pool reader this mirrors). Byte layout reference: AOSP
 * frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h (`ResChunk_header`,
 * `ResStringPool_header`, `ResXMLTree_node`, `ResXMLTree_attrExt`, `ResXMLTree_attribute`). The
 * element/attribute walk was validated byte-for-byte against a real 102KB production manifest (the
 * fork above: 59 components parsed, each class name and action cross-checked against the same file
 * read by an independent implementation) before this port.
 */
object ApkBinaryManifest {

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103

    /** [ResStringPool_header.flags] bit meaning the pool's strings are UTF-8 rather than UTF-16. */
    private const val UTF8_FLAG = 1 shl 8

    /** An attribute's rawValue field with this value means "no raw string form; read the typed value
     *  instead" — otherwise it's a string-pool index to the value's string form. */
    private const val NO_RAW_VALUE = 0xFFFFFFFFL

    /** `Res_value.dataType` for a string: `data` is then a string-pool index. */
    private const val TYPE_STRING = 0x03

    /** `Res_value.dataType` for a plain (decimal/hex-written) integer literal: `data` IS the value,
     *  no string-pool lookup needed. `minSdkVersion`/`targetSdkVersion` compile to one of these. */
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11

    /** `Res_value.dataType` for a resource reference (`@mipmap/ic_launcher`, say): `data` is the
     *  referenced resource's 32-bit id (packageId<<24 | typeId<<16 | entryId), to be looked up in
     *  `resources.arsc` — never a string-pool index. `android:icon` always compiles to one of these
     *  (confirmed against a real APK). */
    private const val TYPE_REFERENCE = 0x01

    /** One declared `<service>` or `<receiver>`: its `android:name` class (as written — possibly
     *  relative, e.g. ".MyService") and every `<action android:name=…>` nested anywhere inside it. */
    data class Component(val tag: String, val className: String, val actions: List<String>)

    /** `android:minSdkVersion`/`android:targetSdkVersion` off a manifest's `<uses-sdk>` element. Either
     *  field is null when the element omits that attribute (or the manifest has no `<uses-sdk>` at all). */
    data class UsesSdk(val minSdkVersion: Int?, val targetSdkVersion: Int?)

    /** A manifest's declared `<uses-permission>`/`<uses-permission-sdk-23>` names and `<uses-feature>`
     *  names — the same two ingredients
     *  [com.looker.droidify.compose.appDetail.detectGoogleServicesDependencies] needs, read here for a
     *  tracked external source's release, which carries no F-Droid-index metadata of its own to read
     *  them from instead. */
    data class PermissionsAndFeatures(val permissionNames: Set<String>, val featureNames: Set<String>)

    /**
     * Every `<service>`/`<receiver>` in [manifest] (compiled binary XML bytes), or null when the bytes
     * can't be parsed as one at all (truncated/corrupt/not binary XML) — the caller should treat null
     * as "couldn't determine", never as "no components". Never throws, since the bytes may come from
     * an untrusted remote file.
     */
    fun components(manifest: ByteArray): List<Component>? = runCatching {
        val components = mutableListOf<Component>()
        // The <service>/<receiver> currently open, collecting nested <action> names until its end tag.
        // Components can't nest inside each other, so one slot (not a stack) is enough.
        var openTag: String? = null
        var openClassName: String? = null
        var openActions: MutableList<String>? = null
        val parsed = walkElements(
            manifest,
            onStart = { element ->
                when (element.name) {
                    "service", "receiver" -> {
                        openTag = element.name
                        openClassName = element.attributes["name"]
                        openActions = mutableListOf()
                    }
                    "action" -> {
                        val actions = openActions
                        if (actions != null) element.attributes["name"]?.let(actions::add)
                    }
                }
            },
            onEnd = { name ->
                if ((name == "service" || name == "receiver") && name == openTag) {
                    val className = openClassName
                    if (className != null) {
                        components.add(Component(name, className, openActions.orEmpty()))
                    }
                    openTag = null
                    openClassName = null
                    openActions = null
                }
            },
        )
        if (!parsed) return@runCatching null
        components
    }.getOrNull()

    /**
     * `<uses-sdk>`'s `minSdkVersion`/`targetSdkVersion` in [manifest], or null when the bytes can't be
     * parsed at all — same "couldn't determine" contract as [components]. A manifest with no `<uses-sdk>`
     * element parses fine and returns [UsesSdk] with both fields null, not null itself. Never throws.
     */
    fun usesSdk(manifest: ByteArray): UsesSdk? = runCatching {
        var min: Int? = null
        var target: Int? = null
        val parsed = walkElements(
            manifest,
            onStart = { element ->
                if (element.name == "uses-sdk") {
                    min = element.intAttributes["minSdkVersion"]
                    target = element.intAttributes["targetSdkVersion"]
                }
            },
            onEnd = {},
        )
        if (!parsed) return@runCatching null
        UsesSdk(min, target)
    }.getOrNull()

    /**
     * The `<application android:icon="…">` attribute's resource id (e.g. `0x7f0b0000`), for resolving
     * the app's real launcher icon out of `resources.arsc` when a catalogue's own repo serves none
     * (see [RemoteApkIconReader]) — or null when the bytes can't be parsed, or the element/attribute is
     * missing. `android:icon` always compiles to a plain resource reference (`Res_value.dataType`
     * [TYPE_REFERENCE]), never a string or literal int, so only that form is read here. Never throws.
     */
    fun applicationIconResourceId(manifest: ByteArray): Int? = runCatching {
        var iconResId: Int? = null
        val parsed = walkElements(
            manifest,
            onStart = { element ->
                if (element.name == "application") {
                    iconResId = element.referenceAttributes["icon"]
                }
            },
            onEnd = {},
        )
        if (!parsed) return@runCatching null
        iconResId
    }.getOrNull()

    /**
     * Every `<uses-permission>`/`<uses-permission-sdk-23>`/`<uses-feature>` name declared in [manifest],
     * or null when the bytes can't be parsed at all — same "couldn't determine" contract as [components]
     * and [usesSdk]. Feeds [com.looker.droidify.compose.appDetail.detectGoogleServicesDependencies] for a
     * tracked external source's release, the same way the F-Droid index's per-package manifest permission/
     * feature lists feed it for a catalogue app. Never throws.
     */
    fun permissionsAndFeatures(manifest: ByteArray): PermissionsAndFeatures? = runCatching {
        val permissions = mutableSetOf<String>()
        val features = mutableSetOf<String>()
        val parsed = walkElements(
            manifest,
            onStart = { element ->
                when (element.name) {
                    "uses-permission", "uses-permission-sdk-23" ->
                        element.attributes["name"]?.let(permissions::add)
                    "uses-feature" -> element.attributes["name"]?.let(features::add)
                }
            },
            onEnd = {},
        )
        if (!parsed) return@runCatching null
        PermissionsAndFeatures(permissions, features)
    }.getOrNull()

    /**
     * Walks every start/end element tag in [manifest], resolving the shared string pool first —
     * the one piece of chunk-format traversal [components] and [usesSdk] both need, kept in this single
     * place so a fix to how elements/attributes are read never has to be made twice. [onStart]/[onEnd]
     * run for every element in document order; returns false when the bytes aren't parseable binary XML
     * (missing/corrupt header, or a start/end element chunk seen before the string pool that names it).
     */
    private inline fun walkElements(
        manifest: ByteArray,
        onStart: (StartElement) -> Unit,
        onEnd: (name: String) -> Unit,
    ): Boolean {
        if (manifest.size < 8 || u16(manifest, 0) != RES_XML_TYPE) return false
        val totalSize = u32(manifest, 4)
        var pos = u16(manifest, 2).toLong()
        val end = minOf(totalSize, manifest.size.toLong())
        var pool: List<String>? = null
        while (pos + 8 <= end) {
            val chunkType = u16(manifest, pos.toInt())
            val chunkSize = u32(manifest, pos.toInt() + 4)
            if (chunkSize <= 0 || pos + chunkSize > end) break
            when (chunkType) {
                // The document's one string pool, holding every tag/attribute name and string value.
                RES_STRING_POOL_TYPE -> if (pool == null) {
                    pool = parseStringPool(manifest, pos.toInt())
                }
                RES_XML_START_ELEMENT_TYPE -> {
                    val strings = pool ?: return false
                    onStart(readStartElement(manifest, pos.toInt(), strings))
                }
                RES_XML_END_ELEMENT_TYPE -> {
                    val strings = pool ?: return false
                    // ResXMLTree_node: header(8) + lineNumber(4) + comment(4), then the end-element
                    // extension: ns(4) + name(4).
                    val nameIndex = u32(manifest, pos.toInt() + 20).toInt()
                    onEnd(strings.getOrNull(nameIndex) ?: "")
                }
            }
            pos += chunkSize
        }
        return true
    }

    /** One parsed start-element: its tag name, its string-valued attributes (e.g. `android:name`), its
     *  integer-valued ones (e.g. `minSdkVersion`), and its resource-reference ones (e.g. `android:icon`)
     *  — a plain-int attribute is compiled with no string-pool entry at all, so it can only ever surface
     *  via [intAttributes], never [attributes]; a reference likewise only ever surfaces via
     *  [referenceAttributes], its `data` being a resource id rather than a value of its own. */
    private class StartElement(
        val name: String,
        val attributes: Map<String, String>,
        val intAttributes: Map<String, Int>,
        val referenceAttributes: Map<String, Int>,
    )

    /** Reads a `RES_XML_START_ELEMENT` chunk: ResXMLTree_node header(8) + lineNumber(4) + comment(4),
     *  then ResXMLTree_attrExt: ns(4) + name(4) + attributeStart(2) + attributeSize(2) +
     *  attributeCount(2) + id/class/styleIndex(6); attributes (each: ns(4) + name(4) + rawValue(4) +
     *  Res_value: size(2) + res0(1) + dataType(1) + data(4)) start attributeStart bytes after the
     *  attrExt (i.e. after the 16-byte node header). */
    private fun readStartElement(manifest: ByteArray, chunkStart: Int, pool: List<String>): StartElement {
        val nameIndex = u32(manifest, chunkStart + 20).toInt()
        val name = pool.getOrNull(nameIndex) ?: ""
        val attributeStart = u16(manifest, chunkStart + 24)
        val attributeSize = u16(manifest, chunkStart + 26)
        val attributeCount = u16(manifest, chunkStart + 28)
        val attributes = HashMap<String, String>(attributeCount)
        val intAttributes = HashMap<String, Int>(attributeCount)
        val referenceAttributes = HashMap<String, Int>(attributeCount)
        val base = chunkStart + 16 + attributeStart
        for (i in 0 until attributeCount) {
            val attr = base + i * attributeSize
            if (attr + 20 > manifest.size) break
            val attrNameIndex = u32(manifest, attr + 4).toInt()
            val attrName = pool.getOrNull(attrNameIndex) ?: continue
            val rawValueIndex = u32(manifest, attr + 8)
            val dataType = manifest[attr + 15].toInt() and 0xFF
            val data = u32(manifest, attr + 16).toInt()
            when {
                rawValueIndex != NO_RAW_VALUE -> {
                    val raw = pool.getOrNull(rawValueIndex.toInt())
                    if (raw != null) {
                        attributes[attrName] = raw
                        raw.toIntOrNull()?.let { intAttributes[attrName] = it }
                    }
                }
                dataType == TYPE_STRING -> pool.getOrNull(data)?.let { attributes[attrName] = it }
                dataType == TYPE_INT_DEC || dataType == TYPE_INT_HEX -> intAttributes[attrName] = data
                dataType == TYPE_REFERENCE -> referenceAttributes[attrName] = data
            }
        }
        return StartElement(name, attributes, intAttributes, referenceAttributes)
    }

    /** Parses a `ResStringPool` chunk starting at [poolStart] — same format, and same reading logic,
     *  as [ApkResourceLocales]' own pool reader (kept private there; binary XML shares the layout). */
    private fun parseStringPool(bytes: ByteArray, poolStart: Int): List<String>? {
        if (poolStart < 0 || poolStart + 28 > bytes.size) return null
        if (u16(bytes, poolStart) != RES_STRING_POOL_TYPE) return null
        val headerSize = u16(bytes, poolStart + 2)
        val stringCount = u32(bytes, poolStart + 8).toInt()
        val flags = u32(bytes, poolStart + 16).toInt()
        val stringsStart = u32(bytes, poolStart + 20).toInt()
        if (stringCount < 0 || stringCount > bytes.size) return null
        val utf8 = (flags and UTF8_FLAG) != 0
        val offsetsStart = poolStart + headerSize
        if (offsetsStart + stringCount * 4 > bytes.size) return null
        val dataStart = poolStart + stringsStart
        return (0 until stringCount).map { i ->
            val entryOffset = u32(bytes, offsetsStart + i * 4).toInt()
            readPoolString(bytes, dataStart, entryOffset, utf8) ?: ""
        }
    }

    /** Reads one string from the pool's data blob — see [ApkResourceLocales]' identical reader for
     *  the length-prefix encoding details. */
    private fun readPoolString(bytes: ByteArray, dataStart: Int, entryByteOffset: Int, utf8: Boolean): String? {
        val start = dataStart + entryByteOffset
        if (start < 0 || start >= bytes.size) return null
        return runCatching {
            if (utf8) {
                val (_, afterCharLen) = decodeLength8(bytes, start)
                val (byteLen, afterByteLen) = decodeLength8(bytes, afterCharLen)
                if (afterByteLen + byteLen > bytes.size) return null
                String(bytes, afterByteLen, byteLen, Charsets.UTF_8)
            } else {
                val (unitLen, afterLen) = decodeLength16(bytes, start)
                val byteLen = unitLen * 2
                if (afterLen + byteLen > bytes.size) return null
                String(bytes, afterLen, byteLen, Charsets.UTF_16LE)
            }
        }.getOrNull()
    }

    private fun decodeLength8(bytes: ByteArray, pos: Int): Pair<Int, Int> {
        val b0 = bytes[pos].toInt() and 0xFF
        return if (b0 and 0x80 != 0) {
            val b1 = bytes[pos + 1].toInt() and 0xFF
            (((b0 and 0x7F) shl 8) or b1) to (pos + 2)
        } else {
            b0 to (pos + 1)
        }
    }

    private fun decodeLength16(bytes: ByteArray, pos: Int): Pair<Int, Int> {
        val u0 = u16(bytes, pos)
        return if (u0 and 0x8000 != 0) {
            val u1 = u16(bytes, pos + 2)
            (((u0 and 0x7FFF) shl 16) or u1) to (pos + 4)
        } else {
            u0 to (pos + 2)
        }
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        u16(b, off).toLong() or (u16(b, off + 2).toLong() shl 16)
}
