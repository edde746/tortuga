package com.edde746.tortuga.addons

import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.DescriptorFlags
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.TransportUrl

/**
 * The addons a new profile starts with: Cinemeta and OpenSubtitles v3, with the manifests and flags
 * stremio-core bundles (`constants::OFFICIAL_ADDONS`, from the MIT-licensed `stremio-official-addons` 2.1.2), so a
 * new profile works before any manifest is fetched. The first refresh ([AddonRegistry.refreshManifests]) or an install
 * of the same transport URL replaces a bundled manifest with the addon's current one, as for any other addon. Flags are
 * derived from this list by transport URL, as in stremio-core.
 */
object DefaultAddons {
    const val CINEMETA_URL = "https://v3-cinemeta.strem.io/manifest.json"
    const val OPENSUBTITLES_URL = "https://opensubtitles-v3.strem.io/manifest.json"

    internal const val CINEMETA_MANIFEST_JSON = """{"id":"com.linvo.cinemeta","version":"3.0.14","name":"Cinemeta","contactEmail":null,"description":"The official addon for movie and series catalogs","logo":null,"background":null,"types":["movie","series"],"resources":["catalog","meta","addon_catalog"],"idPrefixes":["tt"],"catalogs":[{"id":"top","type":"movie","name":"Popular","extra":[{"name":"genre","isRequired":false,"options":["Action","Adventure","Animation","Biography","Comedy","Crime","Documentary","Drama","Family","Fantasy","History","Horror","Mystery","Romance","Sci-Fi","Sport","Thriller","War","Western"],"optionsLimit":1},{"name":"search","isRequired":false,"options":[],"optionsLimit":1},{"name":"skip","isRequired":false,"options":[],"optionsLimit":1}]},{"id":"top","type":"series","name":"Popular","extra":[{"name":"genre","isRequired":false,"options":["Action","Adventure","Animation","Biography","Comedy","Crime","Documentary","Drama","Family","Fantasy","History","Horror","Mystery","Romance","Sci-Fi","Sport","Thriller","War","Western","Reality-TV","Talk-Show","Game-Show"],"optionsLimit":1},{"name":"search","isRequired":false,"options":[],"optionsLimit":1},{"name":"skip","isRequired":false,"options":[],"optionsLimit":1}]},{"id":"year","type":"movie","name":"New","extra":[{"name":"genre","isRequired":true,"options":["2026","2025","2024","2023","2022","2021","2020","2019","2018","2017","2016","2015","2014","2013","2012","2011","2010","2009","2008","2007","2006","2005","2004","2003","2002","2001","2000","1999","1998","1997","1996","1995","1994","1993","1992","1991","1990","1989","1988","1987","1986","1985","1984","1983","1982","1981","1980","1979","1978","1977","1976","1975","1974","1973","1972","1971","1970","1969","1968","1967","1966","1965","1964","1963","1962","1961","1960","1959","1958","1957","1956","1955","1954","1953","1952","1951","1950","1949","1948","1947","1946","1945","1944","1943","1942","1941","1940","1939","1938","1937","1936","1935","1934","1933","1932","1931","1930","1929","1928","1927","1926","1925","1924","1923","1922","1921","1920"],"optionsLimit":1},{"name":"skip","isRequired":false,"options":[],"optionsLimit":1}]},{"id":"year","type":"series","name":"New","extra":[{"name":"genre","isRequired":true,"options":["2026","2025","2024","2023","2022","2021","2020","2019","2018","2017","2016","2015","2014","2013","2012","2011","2010","2009","2008","2007","2006","2005","2004","2003","2002","2001","2000","1999","1998","1997","1996","1995","1994","1993","1992","1991","1990","1989","1988","1987","1986","1985","1984","1983","1982","1981","1980","1979","1978","1977","1976","1975","1974","1973","1972","1971","1970","1969","1968","1967","1966","1965","1964","1963","1962","1961","1960"],"optionsLimit":1},{"name":"skip","isRequired":false,"options":[],"optionsLimit":1}]},{"id":"imdbRating","type":"movie","name":"Featured","extra":[{"name":"genre","isRequired":false,"options":["Action","Adventure","Animation","Biography","Comedy","Crime","Documentary","Drama","Family","Fantasy","History","Horror","Mystery","Romance","Sci-Fi","Sport","Thriller","War","Western"],"optionsLimit":1},{"name":"skip","isRequired":false,"options":[],"optionsLimit":1}]},{"id":"imdbRating","type":"series","name":"Featured","extra":[{"name":"genre","isRequired":false,"options":["Action","Adventure","Animation","Biography","Comedy","Crime","Documentary","Drama","Family","Fantasy","History","Horror","Mystery","Romance","Sci-Fi","Sport","Thriller","War","Western","Reality-TV","Talk-Show","Game-Show"],"optionsLimit":1},{"name":"skip","isRequired":false,"options":[],"optionsLimit":1}]},{"id":"last-videos","type":"series","name":"Last videos","extra":[{"name":"lastVideosIds","isRequired":true,"options":[],"optionsLimit":100}]},{"id":"calendar-videos","type":"series","name":"Calendar videos","extra":[{"name":"calendarVideosIds","isRequired":true,"options":[],"optionsLimit":100}]}],"addonCatalogs":[{"id":"official","type":"all","name":"Official","extraRequired":[],"extraSupported":[]},{"id":"official","type":"movie","name":"Official","extraRequired":[],"extraSupported":[]},{"id":"official","type":"series","name":"Official","extraRequired":[],"extraSupported":[]},{"id":"official","type":"channel","name":"Official","extraRequired":[],"extraSupported":[]},{"id":"community","type":"all","name":"Community","extraRequired":[],"extraSupported":[]},{"id":"community","type":"movie","name":"Community","extraRequired":[],"extraSupported":[]},{"id":"community","type":"series","name":"Community","extraRequired":[],"extraSupported":[]},{"id":"community","type":"channel","name":"Community","extraRequired":[],"extraSupported":[]},{"id":"community","type":"tv","name":"Community","extraRequired":[],"extraSupported":[]},{"id":"community","type":"Podcasts","name":"Community","extraRequired":[],"extraSupported":[]},{"id":"community","type":"other","name":"Community","extraRequired":[],"extraSupported":[]}],"behaviorHints":{"adult":false,"p2p":false,"configurable":false,"configurationRequired":false}}"""

    internal const val OPENSUBTITLES_MANIFEST_JSON = """{"id":"org.stremio.opensubtitlesv3","version":"1.0.0","name":"OpenSubtitles v3","contactEmail":null,"description":"OpenSubtitles v3 Addon for Stremio","logo":"http://www.strem.io/images/addons/opensubtitles-logo.png","background":null,"types":["movie","series"],"resources":["subtitles"],"idPrefixes":["tt"],"catalogs":[],"addonCatalogs":[],"behaviorHints":{"adult":false,"p2p":false,"configurable":false,"configurationRequired":false}}"""

    private fun builtIn(url: String): TransportUrl = checkNotNull(TransportUrl.checked(url)) { "built-in addon URL $url" }

    /** Cinemeta (official, protected) then OpenSubtitles v3 (official). */
    val descriptors: List<Descriptor> = listOf(
        Descriptor(Manifest.decode(CINEMETA_MANIFEST_JSON), builtIn(CINEMETA_URL), DescriptorFlags(official = true, protected = true)),
        Descriptor(Manifest.decode(OPENSUBTITLES_MANIFEST_JSON), builtIn(OPENSUBTITLES_URL), DescriptorFlags(official = true)),
    )

    /** The bundled manifest bodies, for seeding the addon store. */
    internal val manifestJson: Map<String, String> = mapOf(
        CINEMETA_URL to CINEMETA_MANIFEST_JSON,
        OPENSUBTITLES_URL to OPENSUBTITLES_MANIFEST_JSON,
    )

    /** The flags of a built-in addon, or none. */
    fun flags(transportUrl: String): DescriptorFlags =
        descriptors.firstOrNull { it.transportUrl == transportUrl }?.flags ?: DescriptorFlags()
}
