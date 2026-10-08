package com.cts.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Roles: MAKER captures, CHECKER approves held items, OPS runs presentment and
 * settlement, DRAWEE acts for the paying bank. The URL rules are the boundary;
 * hiding a button is never the access control.
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		return http
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health", "/css/**", "/login").permitAll()
				.requestMatchers("/cheques/new").hasRole("MAKER")
				.requestMatchers(HttpMethod.POST, "/cheques").hasRole("MAKER")
				.requestMatchers("/approvals").hasRole("CHECKER")
				.requestMatchers(HttpMethod.POST, "/cheques/*/approve", "/cheques/*/reject").hasRole("CHECKER")
				.requestMatchers(HttpMethod.POST, "/clearing/**", "/settlement/**").hasRole("OPS")
				.requestMatchers("/inward", "/positive-pay").hasRole("DRAWEE")
				.requestMatchers(HttpMethod.POST, "/cheques/*/confirm", "/cheques/*/return", "/positive-pay")
				.hasRole("DRAWEE")
				.anyRequest().authenticated())
			.formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/", true).permitAll())
			.logout(logout -> logout.logoutSuccessUrl("/login?logout"))
			.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	UserDetailsService users(JdbcClient jdbc) {
		return username -> jdbc.sql("select username, password_hash, roles, enabled from app_user where username = ?")
			.param(username)
			.query((rs, i) -> User.withUsername(rs.getString("username"))
				.password(rs.getString("password_hash"))
				.roles(rs.getString("roles").split(","))
				.disabled(!rs.getBoolean("enabled"))
				.build())
			.optional()
			.orElseThrow(() -> new UsernameNotFoundException(username));
	}

}
