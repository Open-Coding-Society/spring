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

import com.nighthawk.spring_portfolio.mvc.person.Person;
import com.nighthawk.spring_portfolio.mvc.person.PersonJpaRepository;

@Controller
@RequestMapping("/mvc/groups")
public class GroupsViewController {
    @Autowired
    private PersonJpaRepository personRepository;

    @GetMapping("/group-tracker")
    public String assignmentTracker(Model model, @AuthenticationPrincipal UserDetails userDetails) {
        String uid = userDetails.getUsername();
        Person user = personRepository.findByUid(uid);
        
        if (user == null || (!user.hasRoleWithName("ROLE_TEACHER") && !user.hasRoleWithName("ROLE_ADMIN"))) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "You must be a teacher or admin to access the group tracker"
            );
        }

        return "group/group";
    }
}