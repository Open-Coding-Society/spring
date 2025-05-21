package com.nighthawk.spring_portfolio.mvc.trains;

import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.aggregation.ArithmeticOperators.Integral;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import com.nighthawk.spring_portfolio.mvc.person.Person;
import com.nighthawk.spring_portfolio.mvc.person.PersonDetailsService;

@Controller
@RequestMapping("/mvc/train")
public class TrainCombinedViewController {
    @Autowired
    private PersonDetailsService personRepository;

    @Autowired
    private TrainJPARepository trainRepository;

    @Autowired
    private TrainStationJPARepository trainStationRepository;

    @Autowired
    private TrainCompanyJPARepository repository;

    @GetMapping("/home")
    public String getTrainHomePage(){
        return "train/home";
    }
}
