package com.schoomano.api;

import com.schoomano.domain.people.IndianMobile;
import com.schoomano.platform.ApiException;
import com.schoomano.security.SignInService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Parent sign-in by phone OTP (ADR 0009). The first three endpoints are public by necessity; they answer
 * identically whether or not a number is known to the school, and are rate limited in the database.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final SignInService signIn;

    public AuthController(SignInService signIn) {
        this.signIn = signIn;
    }

    public record OtpRequest(@NotBlank @Size(max = 30) String phone) { }

    public record OtpVerify(@NotBlank @Size(max = 30) String phone, @NotBlank @Size(max = 20) String code,
                            @Size(max = 200) String deviceLabel) { }

    public record RefreshRequest(@NotBlank @Size(max = 100) String refreshToken) { }

    public record OtpSent(int resendAfterSeconds) { }

    public record TokenView(String tokenType, String accessToken, int expiresIn, String refreshToken, int refreshExpiresIn) {
        static TokenView of(SignInService.TokenPair p) {
            return new TokenView("Bearer", p.accessToken(), p.expiresIn(), p.refreshToken(), p.refreshExpiresIn());
        }
    }

    /** Always 202 when the request is acceptable: the same answer for a known and an unknown number. */
    @PostMapping("/otp/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OtpSent request(@Valid @RequestBody OtpRequest req, HttpServletRequest http) {
        return new OtpSent(signIn.requestCode(phone(req.phone()), http.getRemoteAddr()));
    }

    @PostMapping("/otp/verify")
    public TokenView verify(@Valid @RequestBody OtpVerify req) {
        return TokenView.of(signIn.verifyCode(phone(req.phone()), req.code(), req.deviceLabel()));
    }

    @PostMapping("/token/refresh")
    public TokenView refresh(@Valid @RequestBody RefreshRequest req) {
        return TokenView.of(signIn.refresh(req.refreshToken()));
    }

    /** Ends the session of the access token that is presented (requires being signed in). */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader("Authorization") String authorization) {
        if (authorization.startsWith("Bearer ")) {
            signIn.logout(authorization.substring("Bearer ".length()));
        }
    }

    /** Where this number is signed in: the device the caller is using first. */
    @GetMapping("/sessions")
    public java.util.List<SignInService.MySession> sessions(@RequestHeader("Authorization") String authorization) {
        return signIn.mySessions(token(authorization));
    }

    /** Signs out every other device of the caller's number (a lost phone). */
    @PostMapping("/sessions/revoke-others")
    public java.util.Map<String, Integer> revokeOthers(@RequestHeader("Authorization") String authorization) {
        return java.util.Map.of("revoked", signIn.revokeOtherSessions(token(authorization)));
    }

    /** Signs out one other device of the caller's number. */
    @PostMapping("/sessions/{id}/revoke")
    public java.util.Map<String, Boolean> revokeOne(@RequestHeader("Authorization") String authorization, @org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        if (!signIn.revokeMySession(token(authorization), id)) {
            throw ApiException.notFound();
        }
        return java.util.Map.of("revoked", true);
    }

    private static String token(String authorization) {
        return authorization.startsWith("Bearer ") ? authorization.substring("Bearer ".length()) : "";
    }

    private static String phone(String typed) {
        return IndianMobile.normalise(typed).orElseThrow(() ->
            ApiException.badRequest("invalid_mobile", "Enter a 10-digit Indian mobile number, for example 98450 12345."));
    }
}
