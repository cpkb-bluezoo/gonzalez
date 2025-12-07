/*
 * CompositeByteBufferTest.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.bluezoo.util;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.InvalidMarkException;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Comprehensive unit tests for CompositeByteBuffer.
 * Tests the unified view over underflow and data buffers, buffer state
 * transitions, and all ByteBuffer-like operations.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class CompositeByteBufferTest {

    private CompositeByteBuffer buffer;

    @Before
    public void setUp() {
        buffer = new CompositeByteBuffer();
    }

    // ===================================================================
    // Constructor and Initial State Tests
    // ===================================================================

    @Test
    public void testDefaultConstructor() {
        CompositeByteBuffer cb = new CompositeByteBuffer();
        assertEquals(0, cb.position());
        assertEquals(0, cb.limit());
        assertEquals(0, cb.capacity());
        assertEquals(0, cb.remaining());
        assertFalse(cb.hasRemaining());
    }

    @Test
    public void testConstructorWithMinGrowth() {
        CompositeByteBuffer cb = new CompositeByteBuffer(512);
        assertEquals(0, cb.position());
        assertEquals(0, cb.limit());
        assertEquals(0, cb.capacity());
    }

    // ===================================================================
    // Basic put() and Unified View Tests
    // ===================================================================

    @Test
    public void testPutSingleBuffer() {
        ByteBuffer data = ByteBuffer.wrap("Hello".getBytes());
        buffer.put(data);

        assertEquals(5, buffer.capacity());
        assertEquals(5, buffer.limit());
        assertEquals(5, buffer.position());
        assertEquals(0, buffer.remaining());
    }

    @Test
    public void testPutAndFlip() {
        ByteBuffer data = ByteBuffer.wrap("Hello".getBytes());
        buffer.put(data);
        buffer.flip();

        assertEquals(5, buffer.capacity());
        assertEquals(5, buffer.limit());
        assertEquals(0, buffer.position());
        assertEquals(5, buffer.remaining());
        assertTrue(buffer.hasRemaining());
    }

    @Test
    public void testPutMultipleTimes() {
        ByteBuffer data1 = ByteBuffer.wrap("First".getBytes());
        buffer.put(data1);
        assertEquals(5, buffer.capacity());

        // Second put overwrites
        ByteBuffer data2 = ByteBuffer.wrap("Second".getBytes());
        buffer.put(data2);
        assertEquals(6, buffer.capacity());

        buffer.flip();
        byte[] result = new byte[6];
        buffer.get(result);
        assertEquals("Second", new String(result));
    }

    @Test
    public void testPutWithPartialBuffer() {
        ByteBuffer data = ByteBuffer.wrap("Hello World".getBytes());
        data.position(6); // Start at "World"
        buffer.put(data);

        assertEquals(5, buffer.capacity());
        buffer.flip();

        byte[] result = new byte[5];
        buffer.get(result);
        assertEquals("World", new String(result));
    }

    @Test
    public void testPutWithLimitedBuffer() {
        ByteBuffer data = ByteBuffer.wrap("Hello World".getBytes());
        data.limit(5); // Only "Hello"
        buffer.put(data);

        assertEquals(5, buffer.capacity());
        buffer.flip();

        byte[] result = new byte[5];
        buffer.get(result);
        assertEquals("Hello", new String(result));
    }

    // ===================================================================
    // Compact and Underflow Buffer Tests
    // ===================================================================

    @Test
    public void testCompactWithNoRemainingData() {
        ByteBuffer data = ByteBuffer.wrap("Hello".getBytes());
        buffer.put(data);
        buffer.flip();

        // Read all data
        byte[] result = new byte[5];
        buffer.get(result);
        assertEquals("Hello", new String(result));

        // Compact with nothing remaining
        buffer.compact();
        assertEquals(0, buffer.capacity());
        assertEquals(0, buffer.position());
        assertEquals(0, buffer.limit());
    }

    @Test
    public void testCompactWithRemainingData() {
        ByteBuffer data = ByteBuffer.wrap("Hello".getBytes());
        buffer.put(data);
        buffer.flip();

        // Read only first 2 bytes
        assertEquals((byte) 'H', buffer.get());
        assertEquals((byte) 'e', buffer.get());

        // Compact should save "llo"
        buffer.compact();
        assertEquals(3, buffer.capacity());
        assertEquals(3, buffer.position());
        assertEquals(3, buffer.limit());
    }

    @Test
    public void testUnifiedViewWithUnderflow() {
        // First receive: read partially
        ByteBuffer data1 = ByteBuffer.wrap("Hello".getBytes());
        buffer.put(data1);
        buffer.flip();
        buffer.get(); // Read 'H'
        buffer.get(); // Read 'e'
        buffer.compact(); // Save "llo"

        // Second receive: should see underflow + new data
        ByteBuffer data2 = ByteBuffer.wrap(" World".getBytes());
        buffer.put(data2);

        assertEquals(9, buffer.capacity()); // 3 (underflow) + 6 (new data)
        assertEquals(9, buffer.position());
        assertEquals(9, buffer.limit());

        buffer.flip();
        byte[] result = new byte[9];
        buffer.get(result);
        assertEquals("llo World", new String(result));
    }

    @Test
    public void testMultipleReceiveCycles() {
        // Cycle 1
        buffer.put(ByteBuffer.wrap("ABCD".getBytes()));
        buffer.flip();
        buffer.get(); // Read 'A'
        buffer.compact(); // Save "BCD"

        // Cycle 2
        buffer.put(ByteBuffer.wrap("EFGH".getBytes()));
        buffer.flip();
        assertEquals((byte) 'B', buffer.get());
        assertEquals((byte) 'C', buffer.get());
        buffer.compact(); // Save "DEFGH"

        // Cycle 3
        buffer.put(ByteBuffer.wrap("IJK".getBytes()));
        buffer.flip();
        byte[] result = new byte[8];
        buffer.get(result);
        assertEquals("DEFGHIJK", new String(result));
    }

    // ===================================================================
    // Get Operations Tests
    // ===================================================================

    @Test
    public void testRelativeGet() {
        buffer.put(ByteBuffer.wrap("ABC".getBytes()));
        buffer.flip();

        assertEquals((byte) 'A', buffer.get());
        assertEquals(1, buffer.position());
        assertEquals((byte) 'B', buffer.get());
        assertEquals(2, buffer.position());
        assertEquals((byte) 'C', buffer.get());
        assertEquals(3, buffer.position());
    }

    @Test(expected = BufferUnderflowException.class)
    public void testRelativeGetUnderflow() {
        buffer.put(ByteBuffer.wrap("A".getBytes()));
        buffer.flip();
        buffer.get(); // Read 'A'
        buffer.get(); // Should throw
    }

    @Test
    public void testAbsoluteGet() {
        buffer.put(ByteBuffer.wrap("ABC".getBytes()));
        buffer.flip();

        assertEquals((byte) 'C', buffer.get(2));
        assertEquals(0, buffer.position()); // Position unchanged
        assertEquals((byte) 'A', buffer.get(0));
        assertEquals((byte) 'B', buffer.get(1));
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void testAbsoluteGetOutOfBounds() {
        buffer.put(ByteBuffer.wrap("ABC".getBytes()));
        buffer.flip();
        buffer.get(3); // Should throw
    }

    @Test
    public void testAbsoluteGetAcrossBoundary() {
        // Setup underflow
        buffer.put(ByteBuffer.wrap("ABC".getBytes()));
        buffer.flip();
        buffer.get(); // Read 'A'
        buffer.compact(); // Save "BC"

        // Add new data
        buffer.put(ByteBuffer.wrap("DEF".getBytes()));
        buffer.flip();

        // Test absolute get across underflow/data boundary
        assertEquals((byte) 'B', buffer.get(0)); // From underflow
        assertEquals((byte) 'C', buffer.get(1)); // From underflow
        assertEquals((byte) 'D', buffer.get(2)); // From data buffer
        assertEquals((byte) 'E', buffer.get(3)); // From data buffer
    }

    @Test
    public void testBulkGetArray() {
        buffer.put(ByteBuffer.wrap("Hello World".getBytes()));
        buffer.flip();

        byte[] result = new byte[11];
        buffer.get(result);

        assertEquals("Hello World", new String(result));
        assertEquals(11, buffer.position());
        assertFalse(buffer.hasRemaining());
    }

    @Test
    public void testBulkGetArrayWithOffsetAndLength() {
        buffer.put(ByteBuffer.wrap("ABCDE".getBytes()));
        buffer.flip();

        byte[] result = new byte[10];
        result[0] = '1';
        result[1] = '2';
        result[7] = '7';
        result[8] = '8';
        result[9] = '9';

        buffer.get(result, 2, 5);

        assertEquals("12ABCDE789", new String(result));
        assertEquals(5, buffer.position());
    }

    @Test
    public void testBulkGetFromUnderflowOnly() {
        // Setup
        buffer.put(ByteBuffer.wrap("ABCDEFGH".getBytes()));
        buffer.flip();
        buffer.get(); // Read 'A'
        buffer.compact(); // Save "BCDEFGH"

        buffer.put(ByteBuffer.wrap("123".getBytes()));
        buffer.flip();

        // Bulk read from underflow region
        byte[] result = new byte[5];
        buffer.get(result, 0, 5);

        assertEquals("BCDEF", new String(result));
        assertEquals(5, buffer.position());
    }

    @Test
    public void testBulkGetFromDataOnly() {
        // Setup with underflow, skip past it
        buffer.put(ByteBuffer.wrap("ABC".getBytes()));
        buffer.flip();
        buffer.get(); // Read 'A'
        buffer.compact(); // Save "BC"

        buffer.put(ByteBuffer.wrap("DEFGH".getBytes()));
        buffer.flip();
        buffer.position(2); // Skip underflow ("BC")

        // Bulk read from data buffer only
        byte[] result = new byte[4];
        buffer.get(result, 0, 4);

        assertEquals("DEFG", new String(result));
    }

    @Test
    public void testBulkGetAcrossBoundary() {
        // Setup
        buffer.put(ByteBuffer.wrap("ABCD".getBytes()));
        buffer.flip();
        buffer.get(); // Read 'A'
        buffer.compact(); // Save "BCD"

        buffer.put(ByteBuffer.wrap("EFGH".getBytes()));
        buffer.flip();

        // Bulk read across boundary
        byte[] result = new byte[7];
        buffer.get(result, 0, 7);

        assertEquals("BCDEFGH", new String(result));
        assertEquals(7, buffer.position());
    }

    @Test(expected = BufferUnderflowException.class)
    public void testBulkGetUnderflow() {
        buffer.put(ByteBuffer.wrap("ABC".getBytes()));
        buffer.flip();

        byte[] result = new byte[5];
        buffer.get(result); // Should throw
    }

    // ===================================================================
    // Position, Limit, Capacity Tests
    // ===================================================================

    @Test
    public void testPositionSetter() {
        buffer.put(ByteBuffer.wrap("ABCDE".getBytes()));
        buffer.flip();

        buffer.position(3);
        assertEquals(3, buffer.position());
        assertEquals((byte) 'D', buffer.get());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPositionSetterNegative() {
        buffer.put(ByteBuffer.wrap("ABC".getBytes()));
        buffer.flip();
        buffer.position(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPositionSetterBeyondLimit() {
        buffer.put(ByteBuffer.wrap("ABC".getBytes()));
        buffer.flip();
        buffer.position(4);
    }

    @Test
    public void testLimitSetter() {
        buffer.put(ByteBuffer.wrap("ABCDE".getBytes()));
        buffer.flip();

        buffer.limit(3);
        assertEquals(3, buffer.limit());
        assertEquals(3, buffer.remaining());

        byte[] result = new byte[3];
        buffer.get(result);
        assertEquals("ABC", new String(result));
    }

    @Test
    public void testLimitSetterAdjustsPosition() {
        buffer.put(ByteBuffer.wrap("ABCDE".getBytes()));
        buffer.flip();
        buffer.position(4);

        buffer.limit(3);
        assertEquals(3, buffer.position()); // Position adjusted to limit
    }

    // ===================================================================
    // Mark and Reset Tests
    // ===================================================================

    @Test
    public void testMarkAndReset() {
        buffer.put(ByteBuffer.wrap("ABCDE".getBytes()));
        buffer.flip();

        buffer.get(); // Read 'A'
        buffer.mark();
        buffer.get(); // Read 'B'
        buffer.get(); // Read 'C'

        buffer.reset();
        assertEquals(1, buffer.position());
        assertEquals((byte) 'B', buffer.get());
    }

    @Test(expected = InvalidMarkException.class)
    public void testResetWithoutMark() {
        buffer.put(ByteBuffer.wrap("ABC".getBytes()));
        buffer.flip();
        buffer.reset(); // Should throw
    }

    @Test
    public void testMarkDiscardedByPosition() {
        buffer.put(ByteBuffer.wrap("ABCDE".getBytes()));
        buffer.flip();

        buffer.position(3);
        buffer.mark();
        buffer.position(1); // Before mark

        try {
            buffer.reset(); // Should throw because mark was discarded
            fail("Expected InvalidMarkException");
        } catch (InvalidMarkException e) {
            // Expected
        }
    }

    @Test
    public void testMarkDiscardedByClear() {
        buffer.put(ByteBuffer.wrap("ABC".getBytes()));
        buffer.flip();

        buffer.mark();
        buffer.clear();

        try {
            buffer.reset();
            fail("Expected InvalidMarkException");
        } catch (InvalidMarkException e) {
            // Expected
        }
    }

    @Test
    public void testMarkDiscardedByFlip() {
        buffer.put(ByteBuffer.wrap("ABC".getBytes()));
        buffer.flip();

        buffer.mark();
        buffer.flip();

        try {
            buffer.reset();
            fail("Expected InvalidMarkException");
        } catch (InvalidMarkException e) {
            // Expected
        }
    }

    @Test
    public void testMarkDiscardedByRewind() {
        buffer.put(ByteBuffer.wrap("ABC".getBytes()));
        buffer.flip();

        buffer.position(2);
        buffer.mark();
        buffer.rewind();

        try {
            buffer.reset();
            fail("Expected InvalidMarkException");
        } catch (InvalidMarkException e) {
            // Expected
        }
    }

    @Test
    public void testMarkDiscardedByCompact() {
        buffer.put(ByteBuffer.wrap("ABC".getBytes()));
        buffer.flip();

        buffer.mark();
        buffer.compact();

        try {
            buffer.reset();
            fail("Expected InvalidMarkException");
        } catch (InvalidMarkException e) {
            // Expected
        }
    }

    // ===================================================================
    // Clear, Flip, Rewind Tests
    // ===================================================================

    @Test
    public void testClear() {
        buffer.put(ByteBuffer.wrap("ABCDE".getBytes()));
        buffer.flip();
        buffer.get();
        buffer.get();

        buffer.clear();

        assertEquals(0, buffer.position());
        assertEquals(5, buffer.limit());
        assertEquals(5, buffer.capacity());
    }

    @Test
    public void testFlip() {
        buffer.put(ByteBuffer.wrap("ABCDE".getBytes()));
        assertEquals(5, buffer.position());

        buffer.flip();

        assertEquals(0, buffer.position());
        assertEquals(5, buffer.limit());
    }

    @Test
    public void testRewind() {
        buffer.put(ByteBuffer.wrap("ABCDE".getBytes()));
        buffer.flip();
        buffer.get();
        buffer.get();
        buffer.get();

        buffer.rewind();

        assertEquals(0, buffer.position());
        assertEquals(5, buffer.limit());
        assertEquals((byte) 'A', buffer.get());
    }

    // ===================================================================
    // Slice and Duplicate Tests
    // ===================================================================

    @Test
    public void testSlice() {
        buffer.put(ByteBuffer.wrap("ABCDE".getBytes()));
        buffer.flip();

        CompositeByteBuffer sliced = buffer.slice();

        // Sliced buffer has same state
        assertEquals(buffer.position(), sliced.position());
        assertEquals(buffer.limit(), sliced.limit());
        assertEquals(buffer.capacity(), sliced.capacity());

        // But independent position
        sliced.get();
        assertEquals(0, buffer.position());
        assertEquals(1, sliced.position());

        // Shares content
        assertEquals((byte) 'B', sliced.get());
        buffer.position(1);
        assertEquals((byte) 'B', buffer.get());
    }

    @Test
    public void testDuplicate() {
        buffer.put(ByteBuffer.wrap("ABCDE".getBytes()));
        buffer.flip();
        buffer.mark();

        CompositeByteBuffer dup = buffer.duplicate();

        // Same state
        assertEquals(buffer.position(), dup.position());
        assertEquals(buffer.limit(), dup.limit());
        assertEquals(buffer.capacity(), dup.capacity());

        // Independent position
        dup.get();
        assertEquals(0, buffer.position());
        assertEquals(1, dup.position());

        // Mark is copied (unlike slice)
        dup.reset();
        assertEquals(0, dup.position());
    }

    // ===================================================================
    // Array Access Tests
    // ===================================================================

    @Test
    public void testHasArray() {
        assertFalse(buffer.hasArray());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testArray() {
        buffer.array();
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testArrayOffset() {
        buffer.arrayOffset();
    }

    // ===================================================================
    // Edge Cases and Special Scenarios
    // ===================================================================

    @Test
    public void testEmptyBuffer() {
        assertEquals(0, buffer.capacity());
        assertEquals(0, buffer.limit());
        assertEquals(0, buffer.position());
        assertFalse(buffer.hasRemaining());
    }

    @Test
    public void testPutEmptyBuffer() {
        ByteBuffer empty = ByteBuffer.allocate(0);
        buffer.put(empty);

        assertEquals(0, buffer.capacity());
        assertFalse(buffer.hasRemaining());
    }

    @Test
    public void testCompactGrowsUnderflowBuffer() {
        // Small buffer initially
        CompositeByteBuffer cb = new CompositeByteBuffer(16);

        // Put large data
        byte[] largeData = new byte[1024];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }

        cb.put(ByteBuffer.wrap(largeData));
        cb.flip();

        // Read a few bytes
        for (int i = 0; i < 10; i++) {
            cb.get();
        }

        // Compact should grow underflow to fit remaining 1014 bytes
        cb.compact();
        assertEquals(1014, cb.capacity());

        // Verify data integrity
        cb.put(ByteBuffer.wrap(new byte[10]));
        cb.flip();

        for (int i = 0; i < 1014; i++) {
            assertEquals((byte) ((i + 10) % 256), cb.get());
        }
    }

    @Test
    public void testReadingAcrossBoundaryMultipleTimes() {
        // Create scenario with underflow
        buffer.put(ByteBuffer.wrap("ABC".getBytes()));
        buffer.flip();
        buffer.get(); // Read 'A'
        buffer.compact(); // Save "BC"

        buffer.put(ByteBuffer.wrap("DEFGH".getBytes()));
        buffer.flip();

        // Read across boundary multiple times
        assertEquals((byte) 'B', buffer.get()); // From underflow
        assertEquals((byte) 'C', buffer.get()); // From underflow
        assertEquals((byte) 'D', buffer.get()); // From data
        assertEquals((byte) 'E', buffer.get()); // From data

        // Read again
        buffer.position(0);
        byte[] result = new byte[7];
        buffer.get(result);
        assertEquals("BCDEFGH", new String(result));
    }

    @Test
    public void testMethodChaining() {
        buffer.put(ByteBuffer.wrap("ABCDE".getBytes()))
              .flip()
              .mark()
              .position(3)
              .limit(5)
              .rewind();

        assertEquals(0, buffer.position());
        assertEquals(5, buffer.limit());
    }

    @Test
    public void testComplexWorkflow() {
        // Simulate real streaming scenario
        byte[] chunk1 = "<?xml version=\"1.0\"?>".getBytes();
        byte[] chunk2 = "<root>".getBytes();
        byte[] chunk3 = "<element>text</element>".getBytes();
        byte[] chunk4 = "</root>".getBytes();

        // Receive chunk 1, process partially
        buffer.put(ByteBuffer.wrap(chunk1));
        buffer.flip();
        byte[] header = new byte[5];
        buffer.get(header); // Read "<?xml"
        assertEquals("<?xml", new String(header));
        buffer.compact();

        // Receive chunk 2, process everything
        buffer.put(ByteBuffer.wrap(chunk2));
        buffer.flip();
        int totalAvailable = buffer.remaining();
        assertTrue(totalAvailable > chunk1.length - 5); // Has leftover + new
        buffer.position(totalAvailable); // Consume all
        buffer.compact();

        // Receive chunk 3
        buffer.put(ByteBuffer.wrap(chunk3));
        buffer.flip();
        assertTrue(buffer.remaining() >= chunk3.length);
        buffer.get(new byte[10]); // Partial read
        buffer.compact();

        // Receive chunk 4
        buffer.put(ByteBuffer.wrap(chunk4));
        buffer.flip();
        assertTrue(buffer.hasRemaining());
    }
}

