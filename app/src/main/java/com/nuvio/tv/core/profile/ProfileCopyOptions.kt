package com.nuvio.tv.core.profile

/**
 * Granular options for copying configuration from an existing profile to a newly created or target profile.
 *
 * Replaces the previous single `copyProviderCredentials` boolean per specification requirements:
 *
 * | Rubrique          | Contenu                                                 | Valeur initiale |
 * |-------------------|---------------------------------------------------------|-----------------|
 * | IPTV              | Connexions, playlists et préférences IPTV               | Désactivée      |
 * | Autres connexions | Clés et connexions autorisées à être copiées            | Désactivée      |
 * | Réglages          | Apparence, lecture et accueil                           | Activée         |
 * | Favoris           | Favoris et listes locales                               | Désactivée      |
 * | Historique        | Contenus vus et progression locale                      | Désactivée      |
 */
data class ProfileCopyOptions(
    /** Copy IPTV accounts, playlists, and category selections. */
    val copyIptv: Boolean = false,

    /** Copy non-IPTV provider API keys (Debrid, MDBList, etc.). */
    val copyOtherConnections: Boolean = false,

    /** Copy visual settings, player preferences, and home layout. */
    val copySettings: Boolean = true,

    /** Copy local saved library items and favorites. */
    val copyFavorites: Boolean = false,

    /** Copy local watch progress and history. */
    val copyHistory: Boolean = false
) {
    /** Whether any data is selected for copying at all. */
    val hasAnySelection: Boolean
        get() = copyIptv || copyOtherConnections || copySettings || copyFavorites || copyHistory

    companion object {
        /** Default configuration for a fresh profile copy. */
        val DEFAULT = ProfileCopyOptions()

        /** Full copy (used for local backups or explicit complete replication). */
        val ALL = ProfileCopyOptions(
            copyIptv = true,
            copyOtherConnections = true,
            copySettings = true,
            copyFavorites = true,
            copyHistory = true
        )
    }
}
