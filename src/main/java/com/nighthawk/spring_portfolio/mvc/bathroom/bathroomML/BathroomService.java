package com.nighthawk.spring_portfolio.mvc.bathroom.bathroomML;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.nighthawk.spring_portfolio.mvc.bathroom.Tinkle;
import com.nighthawk.spring_portfolio.mvc.bathroom.TinkleJPARepository;

@Service
public class BathroomService {
    @Autowired
    private TinkleJPARepository tinkleJPARepository;

    public List<Tinkle> getAllLogs() {
        return tinkleJPARepository.findAll();
    }
}
