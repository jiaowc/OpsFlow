package com.opsflow.service;

import com.opsflow.api.dto.CredentialDTO;

import java.util.List;

public interface CredentialService {

    CredentialDTO create(CredentialDTO request);

    List<CredentialDTO> list(String credentialType);

    CredentialDTO getById(Long id);

    CredentialDTO update(Long id, CredentialDTO request);

    boolean delete(Long id);
}
