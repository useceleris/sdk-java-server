package com.useceleris.server;

/**
 * Grants access to one segment. {@link Claims.Builder#build()} validates it.
 *
 * @param segmentId the segment's id: non-empty, without CR, LF or unpaired UTF-16 surrogates
 * @param read whether the credentials may receive the segment's messages
 * @param write whether the credentials may publish to the segment
 */
public record SegmentClaim(String segmentId, boolean read, boolean write) {}
