package it.unconditional;

public class FactoryConfig {
    public Client client(Runnable service) { return new Client(service); }

    public record Client(Runnable service) {
        public void use() { service.run(); }
    }
}
