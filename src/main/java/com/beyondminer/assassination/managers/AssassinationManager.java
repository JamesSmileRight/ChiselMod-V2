package com.beyondminer.assassination.managers;

import com.beyondminer.assassination.models.AssassinationContract;
import com.beyondminer.bounty.database.DatabaseManager;
import com.beyondminer.bounty.managers.BountyManager;

import java.util.List;
import java.util.UUID;

public class AssassinationManager {

    public static final long CONTRACT_DURATION_MS = 24L * 60L * 60L * 1000L;

    private final DatabaseManager database;
    private final BountyManager bountyManager;

    public enum HitRequestResult {
        SUCCESS,
        TARGET_ALREADY_HAS_ACTIVE_HIT
    }

    public enum HitAcceptResult {
        SUCCESS,
        TARGET_HAS_NO_ACTIVE_HIT,
        HIT_ALREADY_ACCEPTED,
        REQUESTER_CANNOT_ACCEPT_OWN_HIT,
        HITMAN_ALREADY_HAS_ACTIVE_HIT
    }

    public record HitKillResult(AssassinationContract contract) {
    }

    public AssassinationManager(DatabaseManager database, BountyManager bountyManager) {
        this.database = database;
        this.bountyManager = bountyManager;
    }

    public synchronized HitRequestResult requestHit(UUID requesterUuid, String requesterName,
                                                    UUID targetUuid, String targetName, int amount) {
        long now = System.currentTimeMillis();

        if (database.getHitContractByTarget(targetUuid, now) != null) {
            return HitRequestResult.TARGET_ALREADY_HAS_ACTIVE_HIT;
        }

        boolean inserted = database.insertHitContract(
                requesterUuid,
                requesterName,
                targetUuid,
                targetName,
                amount,
                now,
                now + CONTRACT_DURATION_MS
        );

        if (!inserted) {
            return HitRequestResult.TARGET_ALREADY_HAS_ACTIVE_HIT;
        }

        return HitRequestResult.SUCCESS;
    }

    public synchronized HitAcceptResult acceptHit(UUID hitmanUuid, String hitmanName, UUID targetUuid) {
        long now = System.currentTimeMillis();

        if (database.getAssignedHitContract(hitmanUuid, now) != null) {
            return HitAcceptResult.HITMAN_ALREADY_HAS_ACTIVE_HIT;
        }

        AssassinationContract contract = database.getHitContractByTarget(targetUuid, now);
        if (contract == null) {
            return HitAcceptResult.TARGET_HAS_NO_ACTIVE_HIT;
        }

        if (contract.isAccepted()) {
            return HitAcceptResult.HIT_ALREADY_ACCEPTED;
        }

        if (hitmanUuid.equals(contract.getRequesterUuid())) {
            return HitAcceptResult.REQUESTER_CANNOT_ACCEPT_OWN_HIT;
        }

        boolean accepted = database.acceptHitContract(contract.getId(), hitmanUuid, hitmanName, now);
        if (!accepted) {
            AssassinationContract refreshed = database.getHitContractByTarget(targetUuid, now);
            if (refreshed == null) {
                return HitAcceptResult.TARGET_HAS_NO_ACTIVE_HIT;
            }
            if (refreshed.isAccepted()) {
                return HitAcceptResult.HIT_ALREADY_ACCEPTED;
            }
            return HitAcceptResult.TARGET_HAS_NO_ACTIVE_HIT;
        }

        return HitAcceptResult.SUCCESS;
    }

    public synchronized List<AssassinationContract> getOpenHits() {
        return database.getOpenHitContracts(System.currentTimeMillis());
    }

    public synchronized AssassinationContract getAssignedHit(UUID hitmanUuid) {
        return database.getAssignedHitContract(hitmanUuid, System.currentTimeMillis());
    }

    public synchronized AssassinationContract getActiveHit(UUID targetUuid) {
        return database.getHitContractByTarget(targetUuid, System.currentTimeMillis());
    }

    public synchronized boolean isAcceptedContractKill(UUID killerUuid, UUID victimUuid) {
        AssassinationContract contract = getActiveHit(victimUuid);
        return contract != null
                && contract.isAccepted()
                && killerUuid.equals(contract.getAcceptedByUuid());
    }

    public synchronized HitKillResult handleContractKill(UUID killerUuid, UUID victimUuid) {
        AssassinationContract contract = getActiveHit(victimUuid);
        if (contract == null || !contract.isAccepted()) {
            return null;
        }

        if (!killerUuid.equals(contract.getAcceptedByUuid())) {
            return null;
        }

        bountyManager.addBounty(killerUuid, contract.getAmount());
        database.deleteHitContract(contract.getId());
        return new HitKillResult(contract);
    }

    /**
     * Expired hits are removed and converted into a direct bounty on the surviving target.
     */
    public synchronized List<AssassinationContract> expireAndApplySurvivorBounties() {
        List<AssassinationContract> expired = database.getExpiredHitContracts(System.currentTimeMillis());
        for (AssassinationContract contract : expired) {
            bountyManager.addBounty(contract.getTargetUuid(), contract.getAmount());
            database.deleteHitContract(contract.getId());
        }
        return expired;
    }

    public static String formatRemaining(long remainingMs) {
        long safeRemaining = Math.max(0L, remainingMs);
        long totalMinutes = safeRemaining / (60L * 1000L);
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        return hours + "h " + minutes + "m";
    }
}
