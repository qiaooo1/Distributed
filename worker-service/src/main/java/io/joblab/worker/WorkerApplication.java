package io.joblab.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import io.joblab.messaging.MessagingConfiguration;
import org.springframework.context.annotation.Import;

@Import(MessagingConfiguration.class)
@SpringBootApplication
public class WorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
