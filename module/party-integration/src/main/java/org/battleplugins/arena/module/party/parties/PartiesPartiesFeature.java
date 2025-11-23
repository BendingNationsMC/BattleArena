package org.battleplugins.arena.module.party.parties;

import com.alessiodp.parties.api.Parties;
import com.alessiodp.parties.api.interfaces.PartyPlayer;
import org.battleplugins.arena.feature.PluginFeature;
import org.battleplugins.arena.feature.party.PartiesFeature;
import org.battleplugins.arena.feature.party.Party;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class PartiesPartiesFeature extends PluginFeature<PartiesPartiesFeature> implements PartiesFeature {

    public PartiesPartiesFeature() {
        super("Parties");
    }

    @Override
    public @Nullable Party getParty(UUID uuid) {
        com.alessiodp.parties.api.interfaces.Party party = resolveParty(uuid);
        if (party == null) {
            return null;
        }

        return new PartiesParty(party);
    }

    static @Nullable com.alessiodp.parties.api.interfaces.Party resolveParty(UUID uuid) {
        com.alessiodp.parties.api.interfaces.PartiesAPI api;
        try {
            api = Parties.getApi();
        } catch (IllegalStateException ex) {
            return null;
        }

        com.alessiodp.parties.api.interfaces.Party party = api.getPartyOfPlayer(uuid);
        if (party != null) {
            return party;
        }

        PartyPlayer partyPlayer = api.getPartyPlayer(uuid);
        if (partyPlayer == null || !partyPlayer.isInParty()) {
            return null;
        }

        UUID partyId = partyPlayer.getPartyId();
        if (partyId != null) {
            party = api.getParty(partyId);
            if (party != null) {
                return party;
            }
        }

        String partyName = partyPlayer.getPartyName();
        if (partyName != null && !partyName.isEmpty()) {
            party = api.getParty(partyName);
        }

        return party;
    }
}
