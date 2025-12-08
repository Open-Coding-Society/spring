package com.open.spring.mvc.rpg.games;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/mvc/games")
public class GameMvcController {

    @Autowired
    private UnifiedGameRepository gameJpaRepository;

    @GetMapping("/read")
    public String readView(Model model) {
        model.addAttribute("list", gameJpaRepository.findAll());
        return "games/read";
    }
}
