package com.guildworkman.api.services.implmentations;

import com.guildworkman.api.booking.service.SlotReservationService;
import com.guildworkman.api.data.models.SkilledWorker;
import com.guildworkman.api.dto.requests.*;
import com.guildworkman.api.data.models.Address;
import com.guildworkman.api.data.models.Appointment;
import com.guildworkman.api.data.models.Client;
import com.guildworkman.api.data.repository.AddressRepository;
import com.guildworkman.api.data.repository.ClientRepository;
import com.guildworkman.api.dto.responses.*;
import com.guildworkman.api.exceptions.*;
import com.guildworkman.api.services.ServiceUtils.AppointmentService;
import com.guildworkman.api.services.ServiceUtils.ClientService;
import com.guildworkman.api.services.ServiceUtils.SkilledWorkerService;
import com.guildworkman.api.utils.JwtUtils;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class ClientServiceImpl implements ClientService {
    private final ModelMapper modelMapper;
    private final SkilledWorkerService skilledWorkerService;
    private final ClientRepository clientRepository;
    private final AddressRepository addressRepository;
    private final PasswordEncoder passwordEncoder;
    private final SlotReservationService slotReservationService;
    private AppointmentService appointmentService;

    @Autowired
    public void setAppointmentService(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @Override
    public ClientRegistrationResponse registerClient(RegistrationRequest request) {
        System.out.println("hello 😀😀👌");
        validateEmail(request.getEmail());
        validatePassword(request.getPassword());

        Client user = new Client();
        user.setFullName(request.getFullName());
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user = clientRepository.save(user);

        ClientRegistrationResponse response = new ClientRegistrationResponse();
        response.setClientId(user.getId());
        response.setMessage("registration successful");
        return response;

    }


    @Override
    @Transactional
    public BookAppointmentResponse bookAppointment(BookAppointmentRequest bookAppointmentRequest) {
//        Client client = clientRepository.findById(bookAppointmentRequest.getClientId())
//                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
//
//        Appointment appointment = appointmentService.bookAppointment(bookAppointmentRequest);
//        appointment.setClient(client);
//        client.getAppointment().add(appointment);
//        appointmentService.save(appointment);
//        BookAppointmentResponse response =
//                modelMapper.map(appointment, BookAppointmentResponse.class);
//        response.setMessage("Appointment booked successfully");
//        return response;
        Client client = clientRepository.findById(bookAppointmentRequest.getClientId())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        Appointment appointment = appointmentService.bookAppointment(bookAppointmentRequest);
        appointment.setClient(client);
        client.getAppointment().add(appointment);
        appointmentService.save(appointment);
        BookAppointmentResponse response = new BookAppointmentResponse();
        response.setScheduleTime(appointment.getScheduleTime());
        response.setStatus(appointment.getStatus());
        response.setMessage("Appointment booked successfully");

        return response;

    }

    @Override
    @Transactional
    public CancelAppointmentResponse cancelAppointment(Long appointmentId){
        Appointment appointment = appointmentService.findAppointmentById(appointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException("Appointment not found"));

        // Cancelling LEAVES the appointment in place with status CANCELLED.
        // This used to also do client.getAppointment().remove(appointment), and
        // because that relation is orphanRemoval = true, the row was DELETED —
        // so the CANCELLED status was unreachable and the client lost all record
        // of the job. Set the status; don't detach it from the client.
        appointmentService.cancelAppointment(appointment.getId());

        // Hand the slot back. A CANCELLED appointment no longer occupies the
        // worker's calendar, so its reservation must stop occupying it too —
        // otherwise the time could never be rebooked by anyone.
        slotReservationService.releaseForAppointment(appointment.getId());

        CancelAppointmentResponse response = new CancelAppointmentResponse();
        response.setAppointmentId(appointment.getId());
        response.setMessage("Appointment cancelled successfully");
        return response;
    }

    @Override
    @Transactional
    public UpdateAppointmentResponse updateAppointment(Long appointmentId, UpdateAppointmentRequest request) {
        // Previously this looked up a client by request.getClientId() (which no
        // caller sends), re-added the appointment to the client's collection, and
        // never applied the requested status — accept/decline silently did nothing.
        return appointmentService.updateAppointment(appointmentId, request);
    }

    @Override
    @Transactional
    public DeleteAppointmentResponse deleteAppointment(Long appointmentId) {
        Appointment appointment = appointmentService.findAppointmentById(appointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException("Appointment not found"));

        // Free the slot before the appointment row goes: same reasoning as
        // cancelAppointment. The reservation outlives the appointment it pointed
        // at, keeping the slot's history.
        slotReservationService.releaseForAppointment(appointment.getId());

        // Detach the appointment from BOTH sides that own it before deleting.
        //
        // Client.appointment and SkilledWorker.appointment are each
        // @OneToMany(cascade = ALL, orphanRemoval = true, fetch = EAGER). Both are
        // loaded and managed here, so if either collection still holds this
        // appointment at flush time, Hibernate cascades a PERSIST back over it and
        // RESURRECTS the entity — the remove is cancelled and no DELETE is ever
        // issued, while the caller still gets "deleted successfully".
        //
        // Matching on id, not object identity: Appointment has no equals/hashCode,
        // so List.remove(entity) silently no-ops if the collection holds a
        // different instance of the same row.
        detachFromOwners(appointment, appointmentId);
        appointmentService.deleteAppointment(appointment.getId());

        DeleteAppointmentResponse response = new DeleteAppointmentResponse();
        response.setMessage("Appointment deleted successfully");
        return response;
    }

    private void detachFromOwners(Appointment appointment, Long appointmentId) {
        Client client = appointment.getClient();
        if (client != null && client.getAppointment() != null) {
            client.getAppointment().removeIf(a -> appointmentId.equals(a.getId()));
            clientRepository.save(client);
        }
        SkilledWorker worker = appointment.getSkilledWorker();
        if (worker != null && worker.getAppointment() != null) {
            worker.getAppointment().removeIf(a -> appointmentId.equals(a.getId()));
        }
    }
    @Override
    public List<ViewAllAppointmentsResponse> viewAllAppointment(Long id) {
        Client client = clientRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        List<Appointment> appointments = client.getAppointment();

        // A client with no appointments is a valid, empty result — not an error.
        if (appointments == null || appointments.isEmpty()) {
            return List.of();
        }

        return appointments.stream()
                .map(this::toViewAllAppointmentsResponse)
                .collect(Collectors.toList());
    }

    private ViewAllAppointmentsResponse toViewAllAppointmentsResponse(Appointment appointment) {
        ViewAllAppointmentsResponse response = new ViewAllAppointmentsResponse();
        response.setId(appointment.getId());
        response.setStatus(appointment.getStatus());
        response.setCategory(appointment.getCategory());
        response.setScheduleTime(appointment.getScheduleTime());
        response.setAmount(appointment.getAmount());

        SkilledWorker skilledWorker = appointment.getSkilledWorker();
        if (skilledWorker != null) {
            AppointmentWorkerResponse worker = new AppointmentWorkerResponse();
            worker.setId(skilledWorker.getId());
            worker.setFullName(skilledWorker.getFullName());
            worker.setCategory(skilledWorker.getCategory());
            response.setWorker(worker);
        }

        return response;
    }
    private void validateEmail(String email) {
        if (!email.matches( "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")) {
            throw new InvalidEmailFoundException("Invalid Email");
        }
    }
    private static  void validatePassword(String password){
        if (password.length() < 8) {
            throw new InvalidPasswordException("Password must contain at least 8 characters");
        }
        if (!password.matches("[a-zA-Z0-9]*")) {
            throw new InvalidPasswordException("Password must be alphanumeric");
        }
        if (!password.matches(".*\\d.*")) {
            throw new InvalidPasswordException("Password must contain at least one digit");
        }
    }

    @Override
    public Client findById(Long clientId) {
        return clientRepository.findById(clientId).orElseThrow(()-> new GuildWorkmanException("client not found"));
    }

    @Override
    public Long getNumberOfUsers() {
        return clientRepository.count();
    }

    @Override
    public LoginResponse login(LoginRequest loginRequest) {
        String email = loginRequest.getEmail();
        String password = loginRequest.getPassword();
        return checkLoginDetail(email, password);
    }

    private LoginResponse checkLoginDetail(String email, String password) {
        Optional<Client> foundClient = clientRepository.findByEmail(email);

        if (foundClient.isPresent()){
            Client client = foundClient.get();
            if (passwordEncoder.matches(password, client.getPassword())) {
                return loginResponseMapper(client);
            } else {
                throw new GuildWorkmanException("Invalid email or password");
            }
        } else {
            throw new GuildWorkmanException("user with the email "+email+" does not exist");
        }
    }

    private LoginResponse loginResponseMapper(Client client) {
        LoginResponse loginResponse = new LoginResponse();
        String accessToken = JwtUtils.generateAccessToken(client.getId());

        System.out.println("Client ID in response: " + client.getId());
        System.out.println("hello 😀😀👌");


        BeanUtils.copyProperties(client, loginResponse);
        loginResponse.setJwtToken(accessToken);
        loginResponse.setUserId(client.getId());
        loginResponse.setMessage("Login Successful");
        return loginResponse;
    }

    @Override
    public UpdateClientResponse updateClientProfile(UpdateClientRequest updateRequest) {
        if (updateRequest.getClientId() == null) throw new UserNotFoundException("client ID must not be null");

        Client foundClient = clientRepository.findById(updateRequest.getClientId())
                .orElseThrow(()-> new UserNotFoundException("User not found"));

        foundClient.setPassword(passwordEncoder.encode(updateRequest.getPassword()));
        foundClient.setUsername(updateRequest.getUsername());
        foundClient.setPhoneNumber(updateRequest.getPhoneNumber());
        Address address = new Address();
        address.setHouseNumber(updateRequest.getHouseNumber());
        address.setStreet(updateRequest.getStreet());
        address.setArea(updateRequest.getArea());
        foundClient.setAddress(address);
        addressRepository.save(address);

        UpdateClientResponse response = new UpdateClientResponse();
        response.setClientId(updateRequest.getClientId());
        response.setUsername(updateRequest.getUsername());
        response.setPassword(updateRequest.getPassword());
        response.setPassword(updateRequest.getPassword());
        response.setHouseNumber(updateRequest.getHouseNumber());
        response.setStreet(updateRequest.getStreet());
        response.setArea(updateRequest.getArea());

        return response;
    }


}

