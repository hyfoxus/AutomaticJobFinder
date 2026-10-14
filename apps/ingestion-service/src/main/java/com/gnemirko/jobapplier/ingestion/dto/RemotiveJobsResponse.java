package com.gnemirko.jobapplier.ingestion.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RemotiveJobsResponse(List<Item> jobs) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            Long id,
            String url,
            String title,
            @JsonProperty("company_name") String companyName,
            @JsonProperty("candidate_required_location") String location,
            @JsonProperty("publication_date") String publicationDate,
            String description) {
    }
}
