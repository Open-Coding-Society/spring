package com.nighthawk.spring_portfolio.mvc.person;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.nighthawk.spring_portfolio.mvc.person.Email.Email;
import com.nighthawk.spring_portfolio.mvc.person.Email.ResetCode;
import com.nighthawk.spring_portfolio.mvc.person.Email.VerificationCode;
import com.nighthawk.spring_portfolio.mvc.person.HttpRequest.HttpSender;

import io.github.cdimascio.dotenv.Dotenv;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.security.core.GrantedAuthority;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;

import java.util.Arrays;
import java.util.Collections;

import lombok.Getter;

// Built using article: https://docs.spring.io/spring-framework/docs/3.2.x/spring-framework-reference/html/mvc.html
// or similar: https://asbnotebook.com/2020/04/11/spring-boot-thymeleaf-form-validation-example/
@Controller
@RequestMapping("/mvc/person")
public class PersonViewController {
    // Autowired enables Control to connect HTML and POJO Object to database easily for CRUD
    @Autowired
    private PersonDetailsService repository;

    //@Autowired
    //private PersonJpaRepository find;

///////////////////////////////////////////////////////////////////////////////////////////
/// "Read" Get and Post mappings

    @GetMapping("/read")
    public String person(Authentication authentication, Model model) {
        //check user authority
        UserDetails userDetails = (UserDetails)authentication.getPrincipal(); 
        boolean isAdmin = false;
        for (GrantedAuthority authority : userDetails.getAuthorities()) {
            if(String.valueOf("ROLE_ADMIN").equals(authority.getAuthority())){
                isAdmin = true;
                break;
            }
        }
        if (isAdmin == true){
            List<Person> list = repository.listAll();  // Fetch all persons
            model.addAttribute("list", list);  // Add the list to the model for the view
        }
        else {
            Person person = repository.getByUid(userDetails.getUsername());  // Fetch the person by email
            List<Person> list = Collections.singletonList(person);  // Create a single element list
            model.addAttribute("list", list);  // Add the list to the model for the view 
        }
        return "person/read";  // Return the template for displaying persons
    }

    @GetMapping("/read/{id}")
    public String person(Authentication authentication, @PathVariable("id") int id, Model model) {
        //check user authority
        UserDetails userDetails = (UserDetails)authentication.getPrincipal(); 
        boolean isAdmin = false;
        for (GrantedAuthority authority : userDetails.getAuthorities()) {
            if(String.valueOf("ROLE_ADMIN").equals(authority.getAuthority())){
                isAdmin = true;
                break;
            }
        }
        if (isAdmin == true){
            Person person = repository.get(id);  // Fetch the person by ID
            List<Person> list = Arrays.asList(person);  // Convert the single person into a list for consistency
            model.addAttribute("list", list);  // Add the list to the model for the view 
        }
        else if(repository.getByUid(userDetails.getUsername()).getId() == id){
            Person person = repository.getByUid(userDetails.getUsername());  // Fetch the person by email
            List<Person> list = Collections.singletonList(person);  // Create a single element list
            model.addAttribute("list", list);  // Add the list to the model for the view 
        }
        return "person/read";  // Return the template for displaying the person
    }


    /* Gathers the attributes filled out in the form, tests for and retrieves validation error
    @param - Person object with @Valid
    @param - BindingResult object
     */
    @PostMapping("/create")
    public String personSave(@Valid Person person, BindingResult bindingResult) {
        // Validation of Decorated PersonForm attributes
        if (bindingResult.hasErrors()) {
            return "person/create";
        }
        repository.save(person);
        repository.addRoleToPerson(person.getUid(), "ROLE_STUDENT");
        // Redirect to next step
        return "redirect:/mvc/person/read";
    }

    /*  The HTML template Forms and PersonForm attributes are bound
        @return - template for person form
        @param - Person Class
    */
    @GetMapping("/create")
    public String personAdd(Person person) {
        return "person/create";
    }



    @PostMapping("/update")
    public String personUpdateSave(Authentication authentication, @Valid Person person, BindingResult bindingResult) {
        // Check if the user has admin authority
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        boolean isAdmin = userDetails.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        Person personToUpdate = repository.getByUid(person.getUid());
        // If the user is not an admin, they can only update their own details
        if (!isAdmin && !personToUpdate.getId().equals(repository.getByUid(userDetails.getUsername()).getId())) {
            return "redirect:/e#Unauthorized";  // Redirect if user tries to update another person's details
        }
        boolean samePassword = true;
        // Update fields if the new values are provided
        if (person.getPassword() != null && !person.getPassword().isBlank()) {
            personToUpdate.setPassword(person.getPassword());
            samePassword = false;
        }
        if (person.getName() != null && !person.getName().isBlank() && !person.getName().equals(personToUpdate.getName())) {
            personToUpdate.setName(person.getName());
        }
        if (person.getEmail() != null && !person.getEmail().isBlank() && !person.getEmail().equals(personToUpdate.getEmail())) {
            personToUpdate.setEmail(person.getEmail());
        }
        if (person.getKasmServerNeeded() != null && !person.getKasmServerNeeded().equals(personToUpdate.getKasmServerNeeded())) {
            personToUpdate.setKasmServerNeeded(person.getKasmServerNeeded());
        }
        if (person.getSid() != null && !person.getSid().equals(personToUpdate.getSid())) {
            personToUpdate.setSid(person.getSid());
        }
        if (person.getBalance() != null && !person.getBalance().isBlank() && !person.getBalance().equals(personToUpdate.getBalance())) {
            personToUpdate.setBalance(person.getBalance());
        }
                

        // Save the updated person and ensure the roles are correctly maintained
        repository.save(personToUpdate, samePassword);
        repository.addRoleToPerson(person.getUid(), "ROLE_USER");
        repository.addRoleToPerson(person.getUid(), "ROLE_STUDENT");
        return "redirect:/mvc/person/read";  // Redirect to the read page after updating
    }

    @Getter
    public static class PersonRoleDto {
        private String uid;
        PersonRoleDto(String uid){
            this.uid = uid;
        }
    }

    /**
     * Updates a specific role for a person via a RESTful request.
     *
     * @param roleDto the DTO containing the GitHub ID and role name
     * @return String indicating success or failure
     */
    @PostMapping("/update/role")
    public String personRoleUpdateSave(@Valid PersonRoleDto roleDto,@RequestParam("roleName") String roleName) {

        Person personToUpdate = repository.getByUid(roleDto.getUid());
        if (personToUpdate == null) {
            return "person/update-roles";  // Return error if person not found
        }

        System.out.println(roleName);
        repository.addRoleToPerson(roleDto.getUid(), roleName);  // Add the role to the person

        return "redirect:/mvc/person/read"; // Redirect to the read page after updating
    }

    @Getter
    public static class PersonRolesDto {
        private String uid;
        private List<String> roleNames;
    }

    /**
     * Updates multiple roles for a person via a RESTful request.
     *
     * @param rolesDto the DTO containing the GitHub ID and a list of role names
     * @return ResponseEntity indicating success or failure
     */
    @PostMapping("/update/roles")
    public ResponseEntity<Object> personRolesUpdateSave(@RequestBody PersonRolesDto rolesDto) {
        Person personToUpdate = repository.getByUid(rolesDto.getUid());
        if (personToUpdate == null) {
            return new ResponseEntity<>(personToUpdate, HttpStatus.CONFLICT);  // Return error if person not found
        }

        // Add all roles to the person
        for (String roleName : rolesDto.getRoleNames()) { //I will assume that the roleNames is made of
            repository.addRoleToPerson(rolesDto.getUid(), roleName);
        }

        return new ResponseEntity<>(personToUpdate, HttpStatus.OK);  // Return success response
    }

    @GetMapping("/update/{id}")
    public String personUpdate(@PathVariable("id") int id, Model model) {
        model.addAttribute("person", repository.get(id));
        return "person/update";
    }
    
    @GetMapping("/update/roles/{id}")
    public String personUpdateRoles(@PathVariable("id") int id, Model model) {
        PersonRoleDto roleDto = new PersonRoleDto(repository.get(id).getUid());
        model.addAttribute("roleDto", roleDto);
        return "person/update-roles";
    }

    @GetMapping("/update/user")
    public String personUpdate(Authentication authentication, Model model) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        model.addAttribute("person", repository.getByUid(userDetails.getUsername()));  // Add the person to the model
        return "person/update";  // Return the template for the update form
    }

    @GetMapping("/update/roles/user")
    public String personUpdateRoles(Authentication authentication, Model model) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        PersonRoleDto roleDto = new PersonRoleDto(userDetails.getUsername());
        model.addAttribute("roleDto", roleDto);
        return "person/update-roles";
    }

///////////////////////////////////////////////////////////////////////////////////////////
/// "Person-Quiz" Get mappings

    @GetMapping("/person-quiz")
    public String personQuiz(Model model){
        List<Person> list = repository.listAll();  // Fetch all persons
        model.addAttribute("person", list.get((int)(Math.random()*list.size())));  // Add the list to the model for the view
        return "person/person-quiz";
    }

///////////////////////////////////////////////////////////////////////////////////////////
/// "Delete" Get mappings

    @GetMapping("/delete/user")
    public String personDelete(Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        repository.delete(repository.getByUid(userDetails.getUsername()).getId());  // Delete the person by ID
        return "redirect:/logout";  // logout the user
    }

    /**sq
     * Deletes a person by ID.
     *
     * @param id the ID of the person to delete
     * @return redirect to the read page after deletion
     */
    @GetMapping("/delete/{id}")
    public String personDelete(Authentication authentication, @PathVariable("id") long id) {
        //don't redirect to read page if you delete yourself
        //check before deleting from database to avoid imploding the backend
        boolean deletingYourself = false;
        if (repository.getByUid(((UserDetails)authentication.getPrincipal()).getUsername()).getId() == id){
            deletingYourself = true;
        }
        repository.delete(id);  // Delete the person by ID
        if(deletingYourself){
            return "redirect:/logout"; //logout the user
        }
        
        return "person/read";  // Redirect to the read page after deletion
    }

///////////////////////////////////////////////////////////////////////////////////////////
/// "Reset" Post and Get mappings

    @Getter
    public static class PersonPasswordReset {
        private String uid;
    }

    @PostMapping("/reset/start")
    public ResponseEntity<Object> resetPassword(@RequestBody PersonPasswordReset personPasswordReset){
        Person personToReset = repository.getByUid(personPasswordReset.getUid());
        
        //person not found
        if (personToReset == null){
            return new ResponseEntity<Object>(HttpStatus.NO_CONTENT);
        }

        //don't allow people to reset the passwords of admins
        if (personToReset.getRoles().stream().anyMatch(role -> "ROLE_ADMIN".equals(role.getName()))){
            return new ResponseEntity<Object>(HttpStatus.UNAUTHORIZED);
        }

        //dont allow people to send emails/ reset password of default users (such as toby)
        Person[] databasePersons = Person.init();
        for (Person person : databasePersons) {
            if(person.getUid().equals(personToReset.getUid())){
                return new ResponseEntity<Object>(HttpStatus.UNAUTHORIZED);
            }
        }

        // if there is already an active code emailed to a user, don't send a second one
        if(ResetCode.getCodeForUid(personToReset.getUid()) != null){
            return new ResponseEntity<Object>(HttpStatus.TOO_MANY_REQUESTS);
        }

        //finally send a password reset email to the person
        Email.sendPasswordResetEmail(personToReset.getEmail(), ResetCode.GenerateResetCode(personToReset.getUid()));
        return new ResponseEntity<Object>(HttpStatus.OK);
    }

    @Getter
    public static class PersonPasswordResetCode {
        private String uid;
        private String code;
    }

    @PostMapping("/reset/check")
    public ResponseEntity<Object> resetPasswordCheck(@RequestBody PersonPasswordResetCode personPasswordResetCode){
        Person personToReset = repository.getByUid(personPasswordResetCode.getUid());

        //person not found
        if (personToReset == null){
            return new ResponseEntity<Object>(HttpStatus.NO_CONTENT);
        }

        // code to check doesn't exist
        if(personPasswordResetCode.getCode() == null){
            return new ResponseEntity<Object>(HttpStatus.NO_CONTENT);
        }

        if(ResetCode.getCodeForUid(personToReset.getUid()) == null){
            return new ResponseEntity<Object>(HttpStatus.NO_CONTENT);
        }

        //if there is a code submitted for the given uid, and it matches the code that is expected, then reset the users password
        if(ResetCode.getCodeForUid(personToReset.getUid()).equals(personPasswordResetCode.getCode())){
            ResetCode.removeCodeByUid(personToReset.getUid());
            
            final Dotenv dotenv = Dotenv.load();
            final String defaultPassword = dotenv.get("DEFAULT_PASSWORD");
            personToReset.setPassword(defaultPassword);
            repository.save(personToReset, false);

            return new ResponseEntity<Object>(HttpStatus.OK);
        }
        return new ResponseEntity<Object>(HttpStatus.NO_CONTENT);
    }


    @GetMapping("/reset")
    public String reset() {
        return "person/reset";
    }

    @GetMapping("/reset/check")
    public String resetCheck() {
        return "person/resetCheck";
    }

///////////////////////////////////////////////////////////////////////////////////////////
/// "Cookie-Clicker" Post and Get mappings
/// 
    @GetMapping("/cookie-clicker")
    public String cookieClicker(Authentication authentication, Model model) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();

        Person person = repository.getByUid(userDetails.getUsername());  // Fetch the person by email
        List<Person> list = Collections.singletonList(person);  // Create a single element list
        model.addAttribute("list", list);  // Add the list to the model for the view 
        return "person/cookie-clicker";  // Return the template for the update form
    }
    
///////////////////////////////////////////////////////////////////////////////////////////
/// "Verification" Post and Get mappings
/// 

    @Getter
    public static class PersonVerificationBody {
        private String uid;
        private String code;
    }

    @PostMapping("/verification")
    public ResponseEntity<Object> verficiation(@RequestBody PersonVerificationBody personVerificationBody) {
        if(personVerificationBody.getUid() == null){
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(MediaType.APPLICATION_JSON);
            String body = "{\"state\":0}"; //0 == failed
            return new ResponseEntity<Object>(body,responseHeaders,HttpStatus.BAD_REQUEST);
        }

        if(personVerificationBody.getUid().contains("@")){
            //assuming uid is an email
            String code = VerificationCode.GenerateVerificationCode(personVerificationBody.getUid());
            Email.sendVerificationEmail(personVerificationBody.getUid(),code);

            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(MediaType.APPLICATION_JSON);
            String body = "{\"state\":2}"; //2 == email
            return new ResponseEntity<Object>(body,responseHeaders,HttpStatus.OK);
        }
        else{
            if(HttpSender.verifyGithub(personVerificationBody.getUid())==true){
                HttpHeaders responseHeaders = new HttpHeaders();
                responseHeaders.setContentType(MediaType.APPLICATION_JSON);
                String body = "{\"state\":1}"; //1 == success
                return new ResponseEntity<Object>(body,responseHeaders,HttpStatus.OK);
            };
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(MediaType.APPLICATION_JSON);
            String body = "{\"state\":0}"; //0 == failed
            return new ResponseEntity<Object>(body,responseHeaders,HttpStatus.NOT_FOUND);
        }
    }

    @PostMapping("/verification/code")
    public ResponseEntity<Object> verficiationWithCode(@RequestBody PersonVerificationBody personVerificationBody) {

        //person not found
        if (personVerificationBody.getUid() == null){
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(MediaType.APPLICATION_JSON);
            String body = "{\"state\":0}"; //0 == failed
            return new ResponseEntity<Object>(body,responseHeaders,HttpStatus.BAD_REQUEST);
        }

        // code to check doesn't exist
        if(personVerificationBody.getCode() == null){
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(MediaType.APPLICATION_JSON);
            String body = "{\"state\":0}"; //0 == failed
            return new ResponseEntity<Object>(body,responseHeaders,HttpStatus.BAD_REQUEST);
        }

        if(VerificationCode.getCodeForUid(personVerificationBody.getUid()) == null){
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(MediaType.APPLICATION_JSON);
            String body = "{\"state\":0}"; //0 == failed
            return new ResponseEntity<Object>(body,responseHeaders,HttpStatus.NO_CONTENT);
        }

        //if there is a code submitted for the given uid, and it matches the code that is expected, then reset the users password
        if(VerificationCode.getCodeForUid(personVerificationBody.getUid()).equals(personVerificationBody.getCode())){
            VerificationCode.removeCodeByUid(personVerificationBody.getUid());

            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(MediaType.APPLICATION_JSON);
            String body = "{\"state\":1}"; //1 == success
            return new ResponseEntity<Object>(body,responseHeaders,HttpStatus.OK);
        }
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"state\":0}"; //0 == failed
        return new ResponseEntity<Object>(body,responseHeaders,HttpStatus.BAD_REQUEST);
    }

}
