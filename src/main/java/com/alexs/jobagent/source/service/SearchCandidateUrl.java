package com.alexs.jobagent.source.service;

import java.net.URI;

final class SearchCandidateUrl {
    private SearchCandidateUrl() {}

    static String normalize(String raw) {
        try {
            URI uri = URI.create(raw).normalize();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getHost().length() > 253 || uri.toString().length() > 2048) {
                return null;
            }
            return uri.toString();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
