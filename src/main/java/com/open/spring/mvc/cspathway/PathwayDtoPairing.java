package com.open.spring.mvc.cspathway;

/**
 * Data Transfer Object (DTO) representing the body of the pairing code response 
 * returned by the CS Pathway setup report API.  This record is used to facilitate 
 * the pairing process between a public web server and a local client terminal in 
 * the gamified CS Pathway experience.
 *
 * @param code The unique alphanumeric pairing code used by the client to authenticate 
 *             and link with the server session.
 * @param expiresAt The Unix epoch timestamp (in milliseconds) indicating when 
 *                  this pairing code will no longer be valid.
 */
public record PathwayDtoPairing(
    String code, 
    long expiresAt) {
}
