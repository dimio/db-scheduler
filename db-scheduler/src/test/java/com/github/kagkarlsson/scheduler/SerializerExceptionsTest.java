package com.github.kagkarlsson.scheduler;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

import com.github.kagkarlsson.scheduler.exceptions.SerializationException;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import java.io.NotSerializableException;
import java.io.StreamCorruptedException;
import org.junit.jupiter.api.Test;

class SerializerExceptionsTest {
  class NotSerializable {}

  @Test
  public void onDeserializeFailureWillThrowExpectedException() {
    SerializationException serializationException =
        assertThrows(
            SerializationException.class,
            () -> {
              Serializer.DEFAULT_JAVA_SERIALIZER.serialize(new NotSerializable());
            });

    assertEquals("Failed to serialize object", serializationException.getMessage());
    assertThat(serializationException.getCause(), is(instanceOf(NotSerializableException.class)));
  }

  @Test
  public void onSerializeFailureWillThrowExpectedException() {
    SerializationException serializationException =
        assertThrows(
            SerializationException.class,
            () -> {
              Serializer.DEFAULT_JAVA_SERIALIZER.deserialize(
                  NotSerializable.class, new byte[] {1, 2, 3, 4});
            });

    assertEquals("Failed to deserialize object", serializationException.getMessage());
    assertThat(serializationException.getCause(), is(instanceOf(StreamCorruptedException.class)));
  }
}
