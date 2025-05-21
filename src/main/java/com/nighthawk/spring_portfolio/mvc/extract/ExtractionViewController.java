package com.nighthawk.spring_portfolio.mvc.extract;

import java.util.*;

import lombok.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.Convert;

import com.vladmihalcea.hibernate.type.json.JsonType;

///// entity classes
import com.nighthawk.spring_portfolio.mvc.person.Person;

///// repositories
import com.nighthawk.spring_portfolio.mvc.person.PersonJpaRepository;
import com.nighthawk.spring_portfolio.mvc.groups.GroupsJpaRepository;



@Controller
@RequestMapping("mvc/extract")
public class ExtractionViewController {
/////////////////////////////////////////
/// Autowired Jpa Repositories

@Autowired
private PersonJpaRepository personJpaRepository;

@Autowired
private GroupsJpaRepository groupsJpaRepository;

/////////////////////////////////////////
/// Export Objects


//person class based on person table schema (no relationships)
    @Data
    @AllArgsConstructor
    @Convert(attributeName = "person", converter = JsonType.class)
    public class PersonEmpty {
        private Long id;
        private String uid;
        private String password;
        private String email;
        private String name;
        private String pfp;
        private String sid;
        private Boolean kasmServerNeeded;
        private Map<String, Map<String, Object>> stats;
    }

//group class based on group table schema (no relationships)
    @Data
    @AllArgsConstructor
    @Convert(attributeName = "group", converter = JsonType.class)
    public class GroupEmpty {
        private Long id;
        private String name;
        private String period;
    }


/////////////////////////////////////////
/// Single Extracts

    
    @GetMapping("/person/{id}")
    public ResponseEntity<PersonEmpty> extractPersonById(@PathVariable("id") long id){
        if(!personJpaRepository.existsById(id)){
            new ResponseEntity<PersonEmpty>(HttpStatus.NOT_FOUND);
        }
        Person person = personJpaRepository.findById(id).get();
        PersonEmpty personEmpty = new PersonEmpty(
            person.getId(), 
            person.getUid(), 
            person.getPassword(), 
            person.getEmail(), 
            person.getName(), 
            person.getPfp(), 
            person.getSid(),  
            person.getKasmServerNeeded(), 
            person.getStats());
        return new ResponseEntity<PersonEmpty>(personEmpty,HttpStatus.OK);
    }

    @GetMapping("/group/{id}")
    public ResponseEntity<PersonEmpty> extractGroupById(@PathVariable("id") long id){
        if(!personJpaRepository.existsById(id)){
            new ResponseEntity<PersonEmpty>(HttpStatus.NOT_FOUND);
        }
        Person person = personJpaRepository.findById(id).get();
        PersonEmpty personEmpty = new PersonEmpty(
            person.getId(), 
            person.getUid(), 
            person.getPassword(), 
            person.getEmail(), 
            person.getName(), 
            person.getPfp(), 
            person.getSid(), 
            person.getKasmServerNeeded(), 
            person.getStats());
        return new ResponseEntity<PersonEmpty>(personEmpty,HttpStatus.OK);
    }
   
/////////////////////////////////////////
/// Multi Extracts


    @GetMapping("all/person")
    public ResponseEntity<List<PersonEmpty>> extractAllPerson(){
        List<Person> personlList = personJpaRepository.findAll();
        ArrayList<PersonEmpty> personEmpties = new ArrayList<PersonEmpty>(0);
        personlList.stream().forEach(person ->{
            personEmpties.add(new PersonEmpty(
            person.getId(), 
            person.getUid(), 
            person.getPassword(), 
            person.getEmail(), 
            person.getName(), 
            person.getPfp(), 
            person.getSid(), 
            person.getKasmServerNeeded(), 
            person.getStats()));
        });
        return new ResponseEntity<List<PersonEmpty>>(personEmpties,HttpStatus.OK);
    }
}
