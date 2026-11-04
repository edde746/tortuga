@file:OptIn(ExperimentalObjCName::class)

package com.edde746.tortuga.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * An addon's `manifest.json` (stremio-core `types/addon/manifest.rs`).
 *
 * Decoding follows stremio-core, except that the lists ([resources], [catalogs], [addonCatalogs]) are decoded one
 * element at a time: a malformed entry is dropped instead of rejecting the manifest. [version] must be semver, as
 * stremio-core requires.
 */
@Serializable(with = ManifestSerializer::class)
data class Manifest(
    val id: String,
    val version: String,
    val name: String,
    val contactEmail: String? = null,
    /** `descriptionText` in Swift: `description` would clash with `NSObject.description`. */
    @ObjCName("descriptionText")
    val description: String? = null,
    /** Empty or invalid is null (`DefaultOnError<NoneAsEmptyString>`). */
    val logo: String? = null,
    val background: String? = null,
    /** Globally supported types; empty means none. */
    val types: List<String>,
    val resources: List<ManifestResource>,
    /** Globally supported id prefixes; null means any id, and so does an empty list. */
    val idPrefixes: List<String>? = null,
    /** Unique by `(id, type)`, first wins. */
    val catalogs: List<ManifestCatalog> = emptyList(),
    val addonCatalogs: List<ManifestCatalog> = emptyList(),
    val behaviorHints: ManifestBehaviorHints = ManifestBehaviorHints(),
) {
    /**
     * Whether this addon serves [path] (stremio-core `Manifest::is_resource_supported`):
     * - `catalog`/`addon_catalog`: a declared catalog of that type and id accepts the path's extras;
     * - anything else: the resource is declared, its types include the path's type (a short resource uses the
     *   manifest's [types]; a full one without `types` supports none), and the id matches one of the id prefixes
     *   (a short resource uses the manifest's [idPrefixes]; null or empty prefixes match every id).
     */
    fun isResourceSupported(path: ResourcePath): Boolean = when (path.resource) {
        Resources.CATALOG -> catalogs.any { it.type == path.type && it.id == path.id && it.isExtraSupported(path.extra) }
        Resources.ADDON_CATALOG -> addonCatalogs.any { it.type == path.type && it.id == path.id && it.isExtraSupported(path.extra) }
        else -> {
            val resource = resources.firstOrNull { it.name == path.resource }
            if (resource == null) {
                false
            } else {
                val types = when (resource) {
                    is ManifestResource.Short -> types
                    is ManifestResource.Full -> resource.types
                }
                val prefixes = when (resource) {
                    is ManifestResource.Short -> idPrefixes
                    is ManifestResource.Full -> resource.idPrefixes
                }
                val typeSupported = types?.contains(path.type) == true
                val idSupported = prefixes == null || prefixes.isEmpty() || prefixes.any { path.id.startsWith(it) }
                typeSupported && idSupported
            }
        }
    }

    /**
     * Whether the addon receives the user's watch activity: it declares the `player` or `library` resource
     * ([com.edde746.tortuga.addons.AddonEvents]), as stremio-web's `receivesWatchActivity` reads a manifest.
     */
    val receivesWatchActivity: Boolean
        get() = resources.any { it.name == Resources.PLAYER || it.name == Resources.LIBRARY }

    companion object {
        /** Decodes a manifest body. Throws [SerializationException] when it isn't a valid manifest. */
        fun decode(body: String): Manifest = AddonJson.decodeFromString(serializer(), body)

        /** [decode], counting the malformed resources and catalogs left out in [drops]. */
        internal fun decode(body: String, drops: DropCounter): Manifest = ManifestSerializer.read(AddonJson.parseToJsonElement(body), drops)
    }
}

/**
 * One entry of `resources`: the short form (a name; the manifest's types and id prefixes apply) or the full form
 * with its own optional `types` and `idPrefixes`.
 */
@Serializable(with = ManifestResourceSerializer::class)
sealed interface ManifestResource {
    val name: String

    data class Short(override val name: String) : ManifestResource

    data class Full(
        override val name: String,
        /** Null supports no type. */
        val types: List<String>? = null,
        /** Null or empty matches every id. */
        val idPrefixes: List<String>? = null,
    ) : ManifestResource
}

/**
 * A catalog (or addon catalog) of the manifest. [extra] is the normalized list of extra properties: the full `extra`
 * form, or the legacy `extraSupported`/`extraRequired` names turned into properties as stremio-core's
 * `ManifestExtra::iter` does. An invalid full `extra` falls back to the legacy form, as stremio-core's untagged enum
 * does.
 */
@Serializable(with = ManifestCatalogSerializer::class)
data class ManifestCatalog(
    val id: String,
    val type: String,
    val name: String? = null,
    val extra: List<ExtraProp> = emptyList(),
) {
    /** Every value names a declared property, and every required property has a value. */
    fun isExtraSupported(values: List<ExtraValue>): Boolean = areExtraNamesSupported(values.map { it.name })

    fun areExtraNamesSupported(names: List<String>): Boolean =
        names.all { name -> extra.any { it.name == name } } &&
            extra.filter { it.isRequired }.all { prop -> names.any { it == prop.name } }

    fun extraProp(name: String): ExtraProp? = extra.firstOrNull { it.name == name }

    /**
     * Declares the `date` extra (stremio-core `is_epg_guide`): on an addon whose manifest says `epgProvider`, a guide
     * catalog, whose pages with a `date` hold that day's programmes ([com.edde746.tortuga.presentation.guide]).
     */
    val declaresDate: Boolean get() = extraProp(Extras.DATE) != null

    /** The first option of every required property, or null when one has no options. */
    fun defaultRequiredExtra(): List<ExtraValue>? = extra.filter { it.isRequired }.map { prop ->
        ExtraValue(prop.name, prop.options.firstOrNull() ?: return null)
    }
}

/**
 * An extra property of a catalog. `skip` is always normalized to [SKIP] (stremio-core `ExtraPropValid`). A negative
 * [optionsLimit] doesn't decode (stremio-core's is unsigned), so the catalog's full `extra` is malformed.
 */
@Serializable
data class ExtraProp(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String> = emptyList(),
    /** Most values the property takes at once. */
    val optionsLimit: Int = 1,
) {
    init {
        require(optionsLimit >= 0) { "'optionsLimit' must be a non-negative integer" }
    }

    companion object {
        val SKIP = ExtraProp(Extras.SKIP)
    }
}

/**
 * `behaviorHints` of a manifest. [epgProvider]: the addon's catalogs that declare the `date` extra are TV guides
 * ([ManifestCatalog.declaresDate]); the addon SDK's `docs/epg.md`.
 */
@Serializable
data class ManifestBehaviorHints(
    val adult: Boolean = false,
    val p2p: Boolean = false,
    val configurable: Boolean = false,
    val configurationRequired: Boolean = false,
    val epgProvider: Boolean = false,
)

private val SEMVER = Regex(
    "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
        "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?" +
        "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$",
)

internal object ManifestSerializer : JsonTreeSerializer<Manifest>("Manifest") {
    override fun read(element: JsonElement): Manifest = read(element, DropCounter())

    fun read(element: JsonElement, drops: DropCounter): Manifest {
        val o = element.asObject("manifest")
        val version = o.reqString("version")
        if (!SEMVER.matches(version)) throw SerializationException("'version' must be semver")
        return Manifest(
            id = o.reqString("id"),
            version = version,
            name = o.reqString("name"),
            contactEmail = o.optString("contactEmail"),
            description = o.optString("description"),
            logo = o.optUrlLenient("logo"),
            background = o.optUrlLenient("background"),
            types = o.optStringList("types") ?: throw SerializationException("'types' is required"),
            resources = if (o["resources"] == null || o["resources"] == JsonNull) {
                throw SerializationException("'resources' is required")
            } else {
                decodeEach(o["resources"], drops, ManifestResourceSerializer::read)
            },
            idPrefixes = o.optStringList("idPrefixes"),
            catalogs = decodeEach(o["catalogs"], drops, ManifestCatalogSerializer::read).distinctBy { it.id to it.type },
            addonCatalogs = decodeEach(o["addonCatalogs"], drops, ManifestCatalogSerializer::read).distinctBy { it.id to it.type },
            behaviorHints = o["behaviorHints"]?.takeUnless { it is JsonNull }
                ?.let { AddonJson.decodeFromJsonElement(ManifestBehaviorHints.serializer(), it) }
                ?: ManifestBehaviorHints(),
        )
    }

    override fun write(value: Manifest): JsonElement = buildJsonObject {
        put("id", value.id)
        put("version", value.version)
        put("name", value.name)
        putIfNotNull("contactEmail", value.contactEmail)
        putIfNotNull("description", value.description)
        putIfNotNull("logo", value.logo)
        putIfNotNull("background", value.background)
        putStrings("types", value.types)
        put("resources", JsonArray(value.resources.map(ManifestResourceSerializer::write)))
        putStrings("idPrefixes", value.idPrefixes)
        put("catalogs", JsonArray(value.catalogs.map(ManifestCatalogSerializer::write)))
        put("addonCatalogs", JsonArray(value.addonCatalogs.map(ManifestCatalogSerializer::write)))
        put("behaviorHints", AddonJson.encodeToJsonElement(ManifestBehaviorHints.serializer(), value.behaviorHints))
    }
}

internal object ManifestResourceSerializer : JsonTreeSerializer<ManifestResource>("ManifestResource") {
    override fun read(element: JsonElement): ManifestResource = when {
        element is JsonPrimitive && element.isString -> ManifestResource.Short(element.content)
        element is JsonObject -> ManifestResource.Full(
            name = element.reqString("name"),
            types = element.optStringList("types"),
            idPrefixes = element.optStringList("idPrefixes"),
        )
        else -> throw SerializationException("a resource must be a name or an object")
    }

    override fun write(value: ManifestResource): JsonElement = when (value) {
        is ManifestResource.Short -> JsonPrimitive(value.name)
        is ManifestResource.Full -> buildJsonObject {
            put("name", value.name)
            putStrings("types", value.types)
            putStrings("idPrefixes", value.idPrefixes)
        }
    }
}

internal object ManifestCatalogSerializer : JsonTreeSerializer<ManifestCatalog>("ManifestCatalog") {
    override fun read(element: JsonElement): ManifestCatalog {
        val o = element.asObject("catalog")
        return ManifestCatalog(
            id = o.reqString("id"),
            type = o.reqString("type"),
            name = o.optString("name"),
            extra = fullExtra(o) ?: shortExtra(o),
        )
    }

    /** The full form, or null when `extra` is absent or doesn't decode (every property must). */
    private fun fullExtra(o: JsonObject): List<ExtraProp>? {
        val array = o["extra"] as? JsonArray ?: return null
        return try {
            array.map { AddonJson.decodeFromJsonElement(ExtraProp.serializer(), it) }
                .distinctBy { it.name }
                .map { if (it.name == Extras.SKIP) ExtraProp.SKIP else it }
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun shortExtra(o: JsonObject): List<ExtraProp> {
        val required = o.optStringList("extraRequired").orEmpty().distinct()
        val supported = o.optStringList("extraSupported").orEmpty().distinct()
        return supported.map { ExtraProp(name = it, isRequired = it in required) }
    }

    override fun write(value: ManifestCatalog): JsonElement = buildJsonObject {
        put("id", value.id)
        put("type", value.type)
        putIfNotNull("name", value.name)
        put("extra", JsonArray(value.extra.map { AddonJson.encodeToJsonElement(ExtraProp.serializer(), it) }))
    }
}
