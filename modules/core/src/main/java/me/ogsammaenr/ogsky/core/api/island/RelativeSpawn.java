package me.ogsammaenr.ogsky.core.api.island;

/**
 * Compact relative spawn point stored as signed offsets from an island's {@code (centerX, 0, centerZ)} origin
 * with quantized {@code yaw}/{@code pitch} bytes.
 */
public record RelativeSpawn(short relX, short relY, short relZ, byte yawQuantized, byte pitchQuantized) {
	public static RelativeSpawn atCenter(int y) {
		return new RelativeSpawn((short) 0, clampShort(y), (short) 0, (byte) 0, (byte) 0);
	}

	public static RelativeSpawn fromAbsolute(int centerX, int centerZ, double x, double y, double z, float yaw, float pitch) {
		int dx = (int) Math.floor(x) - centerX;
		int dy = (int) Math.floor(y);
		int dz = (int) Math.floor(z) - centerZ;
		return new RelativeSpawn(clampShort(dx), clampShort(dy), clampShort(dz), quantizeAngle(yaw), quantizeAngle(pitch));
	}

	public double worldX(int centerX) {
		return centerX + relX + 0.5D;
	}

	public double worldY() {
		return relY;
	}

	public double worldZ(int centerZ) {
		return centerZ + relZ + 0.5D;
	}

	public int blockX(int centerX) {
		return centerX + relX;
	}

	public int blockY() {
		return relY;
	}

	public int blockZ(int centerZ) {
		return centerZ + relZ;
	}

	public float yaw() {
		return dequantizeAngle(yawQuantized);
	}

	public float pitch() {
		return dequantizeAngle(pitchQuantized);
	}

	public static byte quantizeAngle(float degrees) {
		return (byte) Math.round((degrees % 360.0F) * (256.0F / 360.0F));
	}

	public static float dequantizeAngle(byte quantized) {
		return quantized * (360.0F / 256.0F);
	}

	private static short clampShort(int value) {
		if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
			throw new IllegalArgumentException("Relative spawn coordinate out of short range: " + value);
		}
		return (short) value;
	}
}
