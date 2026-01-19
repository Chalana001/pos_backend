package com.chala.posapp.controller;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/health")
public class HealthController {

    @GetMapping
    public String ok(){
        return "POS Backend OK";
    }
}

