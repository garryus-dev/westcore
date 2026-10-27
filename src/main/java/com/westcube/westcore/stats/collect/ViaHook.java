package com.westcube.westcore.stats.collect;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;

import java.util.UUID;

/** Seule classe qui touche à ViaVersion : chargée uniquement si le plugin est présent. */
final class ViaHook {

    private ViaHook() {}

    /** {nom de version, protocole} du client, null si inconnu. */
    static Object[] version(UUID uuid) {
        ProtocolVersion v = Via.getAPI().getPlayerProtocolVersion(uuid);
        if (v == null || !v.isKnown()) return null;
        return new Object[]{v.getName(), v.getVersion()};
    }
}
