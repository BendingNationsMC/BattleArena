package org.battleplugins.arena.module.party.parties;

import com.alessiodp.parties.api.interfaces.PartyPlayer;
import org.battleplugins.arena.feature.party.Party;
import org.battleplugins.arena.feature.party.PartyMember;

import java.util.UUID;

public class PartiesPartyMember implements PartyMember {
    private final PartyPlayer impl;

    public PartiesPartyMember(PartyPlayer impl) {
        this.impl = impl;
    }

    @Override
    public String getName() {
        return this.impl.getName();
    }

    @Override
    public UUID getUniqueId() {
        return this.impl.getPlayerUUID();
    }

    @Override
    public Party getParty() {
        com.alessiodp.parties.api.interfaces.Party party = PartiesPartiesFeature.resolveParty(this.impl.getPlayerUUID());
        if (party == null) {
            return null;
        }

        return new PartiesParty(party);
    }
}
