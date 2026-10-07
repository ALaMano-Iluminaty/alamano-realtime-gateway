package com.alamano.gateway.location;

// Cuerpo del SEND a /app/location. El id del vendedor nunca viaja aquí: sale del token.
public record LocationMessage(Double latitude, Double longitude) { }
