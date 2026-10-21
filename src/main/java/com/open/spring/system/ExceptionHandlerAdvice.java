package com.open.spring.system;

import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.open.spring.mvc.person.WeakPasswordException;

@ControllerAdvice
public class ExceptionHandlerAdvice {
    
    @ExceptionHandler(JsonProcessingException.class)
    public ResponseEntity<String> jsonProcessingException(JsonProcessingException e) {
        // log exception
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("JSON failed to process");
    } 
    
    @ExceptionHandler(JsonMappingException.class)
    public ResponseEntity<String> jsonMappingException(JsonMappingException e) {
        // log exception
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("JSON failed to map");
    }

    // Every endpoint that saves a new password gets the same 400 and message.
    @ExceptionHandler(WeakPasswordException.class)
    public ResponseEntity<String> weakPasswordException(WeakPasswordException e) {
        JSONObject body = new JSONObject();
        body.put("error", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString());
    }
}
