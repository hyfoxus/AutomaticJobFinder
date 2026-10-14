package com.gnemirko.jobapplier.ingestion.dto;

import java.net.URI;

public record FetchedPage(URI finalUri, int status, String contentType, byte[] body) {
}
