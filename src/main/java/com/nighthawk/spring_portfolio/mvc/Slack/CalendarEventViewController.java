package com.nighthawk.spring_portfolio.mvc.Slack;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/mvc/calendar")
public class CalendarEventViewController {

    @GetMapping("/events")
    public String getAllEvents() {
        return "calendar/calendar_events";
    }
    
}
