package pt.iceman.commsmiddleware.cars.ev;

import pt.iceman.commsmiddleware.cars.BaseCommand;

import java.nio.ByteBuffer;

public class ElectricBased extends BaseCommand {
    private final int batteryLevelPercentage;

    public ElectricBased(ByteBuffer byteBuffer) {
        super(byteBuffer);

        this.batteryLevelPercentage = parseIntValue(byteBuffer);
    }

    public int getBatteryLevelPercentage() {
        return batteryLevelPercentage;
    }
}
