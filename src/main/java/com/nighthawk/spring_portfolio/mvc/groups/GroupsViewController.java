package com.nighthawk.spring_portfolio.mvc.groups;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/mvc/groups")
public class GroupsViewController {
    @GetMapping("/group-tracker")
    public String assignmentTracker(Model model, @AuthenticationPrincipal UserDetails userDetails) {
        return "group/group";
    }
}