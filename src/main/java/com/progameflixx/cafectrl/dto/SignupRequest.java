package com.progameflixx.cafectrl.dto;

import lombok.Data;

@Data
public class SignupRequest {
    private String email;
    private String password;
    private String name;
    private String cafeName;
    private String phone = "";
    private String address = "";
}
