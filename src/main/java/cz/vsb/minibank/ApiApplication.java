package cz.vsb.minibank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

//TODO: I don't think that I have OTP code saved for any client,
// add new repo for Card? Or add (hardcode) dummy OTP code for
// each client. I think that it is better to update our database with Card
@SpringBootApplication
public class ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
