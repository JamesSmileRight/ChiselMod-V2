package com.beyondminer.kingdoms.managers;

import com.beyondminer.kingdoms.database.DatabaseManager;
import com.beyondminer.kingdoms.models.Kingdom;

import java.util.List;
import java.util.UUID;

public class AllyManager {

    public static final int MAX_ALLIES = 2;

    private final KingdomManager kingdomManager;
    private final DatabaseManager databaseManager;

    public enum AllyRequestResult {
        SUCCESS,
        NOT_IN_KINGDOM,
        NOT_LEADER,
        TARGET_NOT_FOUND,
        SAME_KINGDOM,
        ALREADY_ALLIED,
        REQUEST_ALREADY_SENT,
        TARGET_ALREADY_REQUESTED,
        REQUESTER_AT_LIMIT,
        TARGET_AT_LIMIT
    }

    public enum AllyAcceptResult {
        SUCCESS,
        NOT_IN_KINGDOM,
        NOT_LEADER,
        REQUESTER_NOT_FOUND,
        NO_PENDING_REQUEST,
        ALREADY_ALLIED,
        REQUESTER_AT_LIMIT,
        ACCEPTOR_AT_LIMIT
    }

    public enum AllyRemoveResult {
        SUCCESS,
        NOT_IN_KINGDOM,
        NOT_LEADER,
        TARGET_NOT_FOUND,
        NOT_ALLIED
    }

    public AllyManager(KingdomManager kingdomManager, DatabaseManager databaseManager) {
        this.kingdomManager = kingdomManager;
        this.databaseManager = databaseManager;
    }

    public AllyRequestResult requestAlliance(UUID requesterUuid, String targetKingdomName) {
        Kingdom requesterKingdom = kingdomManager.getPlayerKingdom(requesterUuid);
        if (requesterKingdom == null) {
            return AllyRequestResult.NOT_IN_KINGDOM;
        }

        if (!requesterKingdom.getLeader().equals(requesterUuid)) {
            return AllyRequestResult.NOT_LEADER;
        }

        Kingdom targetKingdom = kingdomManager.getKingdom(targetKingdomName);
        if (targetKingdom == null) {
            return AllyRequestResult.TARGET_NOT_FOUND;
        }

        if (requesterKingdom.getName().equalsIgnoreCase(targetKingdom.getName())) {
            return AllyRequestResult.SAME_KINGDOM;
        }

        if (databaseManager.areAllied(requesterKingdom.getName(), targetKingdom.getName())) {
            return AllyRequestResult.ALREADY_ALLIED;
        }

        if (databaseManager.getAllyCount(requesterKingdom.getName()) >= MAX_ALLIES) {
            return AllyRequestResult.REQUESTER_AT_LIMIT;
        }

        if (databaseManager.getAllyCount(targetKingdom.getName()) >= MAX_ALLIES) {
            return AllyRequestResult.TARGET_AT_LIMIT;
        }

        if (databaseManager.hasPendingAllyRequest(requesterKingdom.getName(), targetKingdom.getName())) {
            return AllyRequestResult.REQUEST_ALREADY_SENT;
        }

        if (databaseManager.hasPendingAllyRequest(targetKingdom.getName(), requesterKingdom.getName())) {
            return AllyRequestResult.TARGET_ALREADY_REQUESTED;
        }

        if (!databaseManager.insertAllyRequest(requesterKingdom.getName(), targetKingdom.getName())) {
            return AllyRequestResult.REQUEST_ALREADY_SENT;
        }

        return AllyRequestResult.SUCCESS;
    }

    public AllyAcceptResult acceptAlliance(UUID accepterUuid, String requesterKingdomName) {
        Kingdom accepterKingdom = kingdomManager.getPlayerKingdom(accepterUuid);
        if (accepterKingdom == null) {
            return AllyAcceptResult.NOT_IN_KINGDOM;
        }

        if (!accepterKingdom.getLeader().equals(accepterUuid)) {
            return AllyAcceptResult.NOT_LEADER;
        }

        Kingdom requesterKingdom = kingdomManager.getKingdom(requesterKingdomName);
        if (requesterKingdom == null) {
            return AllyAcceptResult.REQUESTER_NOT_FOUND;
        }

        if (databaseManager.areAllied(requesterKingdom.getName(), accepterKingdom.getName())) {
            return AllyAcceptResult.ALREADY_ALLIED;
        }

        if (!databaseManager.hasPendingAllyRequest(requesterKingdom.getName(), accepterKingdom.getName())) {
            return AllyAcceptResult.NO_PENDING_REQUEST;
        }

        if (databaseManager.getAllyCount(requesterKingdom.getName()) >= MAX_ALLIES) {
            return AllyAcceptResult.REQUESTER_AT_LIMIT;
        }

        if (databaseManager.getAllyCount(accepterKingdom.getName()) >= MAX_ALLIES) {
            return AllyAcceptResult.ACCEPTOR_AT_LIMIT;
        }

        if (!databaseManager.acceptAllyRequest(requesterKingdom.getName(), accepterKingdom.getName())) {
            return AllyAcceptResult.NO_PENDING_REQUEST;
        }

        return AllyAcceptResult.SUCCESS;
    }

    public AllyRemoveResult removeAlliance(UUID removerUuid, String otherKingdomName) {
        Kingdom removerKingdom = kingdomManager.getPlayerKingdom(removerUuid);
        if (removerKingdom == null) {
            return AllyRemoveResult.NOT_IN_KINGDOM;
        }

        if (!removerKingdom.getLeader().equals(removerUuid)) {
            return AllyRemoveResult.NOT_LEADER;
        }

        Kingdom otherKingdom = kingdomManager.getKingdom(otherKingdomName);
        if (otherKingdom == null) {
            return AllyRemoveResult.TARGET_NOT_FOUND;
        }

        if (!databaseManager.areAllied(removerKingdom.getName(), otherKingdom.getName())) {
            return AllyRemoveResult.NOT_ALLIED;
        }

        databaseManager.removeAlly(removerKingdom.getName(), otherKingdom.getName());
        return AllyRemoveResult.SUCCESS;
    }

    public boolean areAllied(String kingdom1, String kingdom2) {
        return databaseManager.areAllied(kingdom1, kingdom2);
    }

    public List<String> getAlliesForKingdom(String kingdomName) {
        return databaseManager.getAllies(kingdomName);
    }

    public List<String> getIncomingRequests(String kingdomName) {
        return databaseManager.getIncomingAllyRequests(kingdomName);
    }
}
