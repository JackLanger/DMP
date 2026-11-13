package de.dmp.server.controller;

import java.util.UUID;

public record TransferCompletion(UUID transferId, UUID documentId, String sha256) {
}
