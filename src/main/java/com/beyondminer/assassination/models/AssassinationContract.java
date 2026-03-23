package com.beyondminer.assassination.models;

import java.util.UUID;

public class AssassinationContract {
    private final long id;
    private final UUID requesterUuid;
    private final String requesterName;
    private final UUID targetUuid;
    private final String targetName;
    private final int amount;
    private final long createdAt;
    private final long expiresAt;
    private final UUID acceptedByUuid;
    private final String acceptedByName;
    private final Long acceptedAt;

    public AssassinationContract(
            long id,
            UUID requesterUuid,
            String requesterName,
            UUID targetUuid,
            String targetName,
            int amount,
            long createdAt,
            long expiresAt,
            UUID acceptedByUuid,
            String acceptedByName,
            Long acceptedAt
    ) {
        this.id = id;
        this.requesterUuid = requesterUuid;
        this.requesterName = requesterName;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.amount = amount;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.acceptedByUuid = acceptedByUuid;
        this.acceptedByName = acceptedByName;
        this.acceptedAt = acceptedAt;
    }

    public long getId() {
        return id;
    }

    public UUID getRequesterUuid() {
        return requesterUuid;
    }

    public String getRequesterName() {
        return requesterName;
    }

    public UUID getTargetUuid() {
        return targetUuid;
    }

    public String getTargetName() {
        return targetName;
    }

    public int getAmount() {
        return amount;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public UUID getAcceptedByUuid() {
        return acceptedByUuid;
    }

    public String getAcceptedByName() {
        return acceptedByName;
    }

    public Long getAcceptedAt() {
        return acceptedAt;
    }

    public boolean isAccepted() {
        return acceptedByUuid != null;
    }
}
