package com.redxela.auth;

import com.redxela.domain.Customer;
import com.redxela.domain.ServiceLevel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

/** Endpoints de cuenta: registro, sesión y nivel de servicio. */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    /** Niveles de servicio disponibles para que un cliente nuevo elija. */
    @GetMapping("/service-levels")
    public List<ServiceLevelDto> serviceLevels() {
        return Arrays.stream(ServiceLevel.values())
                .map(s -> new ServiceLevelDto(s.level(), s.displayName(), s.maxWaitMinutes()))
                .toList();
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerDto register(@Valid @RequestBody RegisterRequest req) {
        return CustomerDto.of(auth.register(req.name(), req.email(), req.password(), req.serviceLevel()));
    }

    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        AuthService.Session s = auth.login(req.email(), req.password());
        return new LoginResponse(s.token(), CustomerDto.of(s.customer()));
    }

    @GetMapping("/auth/me")
    public CustomerDto me(@RequestHeader("Authorization") String authorization) {
        return CustomerDto.of(auth.authenticate(bearer(authorization)));
    }

    @PutMapping("/auth/service-level")
    public CustomerDto changeServiceLevel(@RequestHeader("Authorization") String authorization,
                                          @Valid @RequestBody ServiceLevelRequest req) {
        return CustomerDto.of(auth.changeServiceLevel(bearer(authorization), req.serviceLevel()));
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader("Authorization") String authorization) {
        auth.logout(bearer(authorization));
    }

    private static String bearer(String header) {
        return header.startsWith("Bearer ") ? header.substring(7).trim() : header.trim();
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Email @Size(max = 120) String email,
            @NotBlank @Size(min = 6, max = 72) String password,
            @Min(1) @Max(5) int serviceLevel) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record ServiceLevelRequest(@Min(1) @Max(5) int serviceLevel) {
    }

    public record ServiceLevelDto(int level, String name, int maxWaitMinutes) {
    }

    public record LoginResponse(String token, CustomerDto customer) {
    }

    /** Vista pública del cliente: nunca expone el hash de la contraseña. */
    public record CustomerDto(long id, String name, String email, ServiceLevelDto serviceLevel) {
        static CustomerDto of(Customer c) {
            ServiceLevel s = c.serviceLevel();
            return new CustomerDto(c.id(), c.name(), c.email(),
                    new ServiceLevelDto(s.level(), s.displayName(), s.maxWaitMinutes()));
        }
    }
}
