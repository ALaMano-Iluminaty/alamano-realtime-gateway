package com.alamano.gateway.services;

public record ActiveService(String serviceId, String professionalId, String clientId, String status, long version) { }
