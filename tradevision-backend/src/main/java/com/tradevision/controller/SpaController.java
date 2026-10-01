package com.tradevision.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Forwards all non-API, non-static routes to index.html
 * so Angular router handles client-side navigation on refresh.
 */
@Controller
public class SpaController {

    @RequestMapping(value = {
        "/", "/app/**", "/landing"
    })
    public String spa() {
        return "forward:/index.html";
    }
}
