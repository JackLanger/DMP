package de.dmp.server.controller;

import java.time.Instant;
import java.util.UUID;

public record TransferStatus(UUID transferId, long offset, long length, Instant expiresAt, boolean completed) {
}
