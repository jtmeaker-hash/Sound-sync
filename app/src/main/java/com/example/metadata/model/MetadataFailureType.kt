package com.example.metadata.model

/**
 * Diagnostic failure types distinguishing root causes of metadata pipeline issues (Section 21).
 */
enum class MetadataFailureType(val code: String, val userReadableMessage: String) {
    NO_NETWORK("NO_NETWORK", "No internet connection detected"),
    DNS_FAILURE("DNS_FAILURE", "Domain name resolution failed"),
    TLS_FAILURE("TLS_FAILURE", "TLS/SSL handshake failed"),
    HTTP_ERROR("HTTP_ERROR", "HTTP request returned an error status"),
    RATE_LIMITED("RATE_LIMITED", "API rate limit exceeded, backing off"),
    MALFORMED_REQUEST("MALFORMED_REQUEST", "Request parameters could not be encoded"),
    JSON_PARSE_ERROR("JSON_PARSE_ERROR", "Failed to parse API JSON response"),
    NO_ITUNES_RESULTS("NO_ITUNES_RESULTS", "No matching songs found in iTunes catalog"),
    LOW_CONFIDENCE_MATCH("LOW_CONFIDENCE_MATCH", "Candidate match confidence below acceptance threshold"),
    MBID_NOT_FOUND("MBID_NOT_FOUND", "MusicBrainz release identifier could not be resolved"),
    COVER_ART_NOT_FOUND("COVER_ART_NOT_FOUND", "No front cover artwork found in Cover Art Archive"),
    IMAGE_DOWNLOAD_FAILED("IMAGE_DOWNLOAD_FAILED", "Failed to download cover image bytes"),
    IMAGE_DECODE_FAILED("IMAGE_DECODE_FAILED", "Downloaded image bytes could not be decoded as a valid image"),
    FILE_PERMISSION_REQUIRED("FILE_PERMISSION_REQUIRED", "Audio file is read-only or requires SAF write consent"),
    UNSUPPORTED_TAG_FORMAT("UNSUPPORTED_TAG_FORMAT", "Tagging format not supported for safe lossless writing"),
    TAG_WRITE_FAILED("TAG_WRITE_FAILED", "Failed to write tags to audio file"),
    WRITE_VERIFICATION_FAILED("WRITE_VERIFICATION_FAILED", "Tags or artwork read back from disk did not match written values")
}
