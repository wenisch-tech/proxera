package tech.wenisch.proxera.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import tech.wenisch.proxera.service.UserService;

@Component
public class StartupInitializer implements ApplicationRunner {

    private final UserService userService;

    public StartupInitializer(UserService userService) {
        this.userService = userService;
    }

    @Override
    public void run(ApplicationArguments args) {
        userService.ensureDefaultAdminExists();
    }
}
