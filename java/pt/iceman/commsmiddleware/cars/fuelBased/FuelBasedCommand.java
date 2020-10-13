package pt.iceman.commsmiddleware.cars.fuelBased;

import pt.iceman.commsmiddleware.cars.BaseCommand;

import java.nio.ByteBuffer;

public class FuelBasedCommand extends BaseCommand {
    private final boolean oilPressureLow;
    private final boolean sparkPlugOn;
    private final int rpm;
    private final int fuelLevel;
    private final int engineTemperature;

    public FuelBasedCommand(ByteBuffer bytebuffer) {
        super(bytebuffer);

        byte oilPressure = bytebuffer.get();
        this.oilPressureLow = oilPressure == 127;

        byte sparkPlug = bytebuffer.get();
        this.sparkPlugOn = sparkPlug == 127;

        this.rpm = parseIntValue(bytebuffer);
        this.fuelLevel = parseIntValue(bytebuffer);
        this.engineTemperature = parseIntValue(bytebuffer);
    }

    public boolean isOilPressureLow() {
        return oilPressureLow;
    }

    public boolean isSparkPlugOn() {
        return sparkPlugOn;
    }

    public int getRpm() {
        return rpm;
    }

    public int getFuelLevel() {
        return fuelLevel;
    }

    public int getEngineTemperature() {
        return engineTemperature;
    }
}
