/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.homesoft.exo.extractor.avi;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import androidx.media3.common.Format;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ParsableNalUnitBitArray;
import androidx.media3.extractor.TrackOutput;

import java.io.IOException;

/**
 * Peeks an MP4V stream looking for pixelWidthHeightRatio data
 */
public class Mp4VStreamHandler extends NalStreamHandler {
  @VisibleForTesting
  static final byte SEQUENCE_START_CODE = (byte)0xb0;
  @VisibleForTesting
  static final byte VOP_START_CODE = (byte)0xb6;
  @VisibleForTesting
  static final int LAYER_START_CODE = 0x20;
  private static final float[] ASPECT_RATIO = {0f, 1f, 12f/11f, 10f/11f, 16f/11f, 40f/33f};

  private static final byte SIMPLE_PROFILE_MASK = 0b1111;
  private static final int SHAPE_TYPE_GRAYSCALE = 3;
  @VisibleForTesting
  static final int Extended_PAR = 0xf;

  private final Format.Builder formatBuilder;

  @VisibleForTesting()
  float pixelWidthHeightRatio = 1f;
  int vopTimeIncrementBits;

  public Mp4VStreamHandler(int id, long durationUs, @NonNull TrackOutput trackOutput,
                           @NonNull Format.Builder formatBuilder) {
    super(id, durationUs, trackOutput, 12);
    this.formatBuilder = formatBuilder;
  }

  @Override
  boolean skip(byte nalType) {
    return nalType != SEQUENCE_START_CODE && (!usePicClock || nalType != VOP_START_CODE);
  }

  void readMarkerBit(@NonNull final ParsableNalUnitBitArray in) {
    if (!in.readBit()) {
      throw new IllegalStateException("Marker Bit false");
    }
  }

  @VisibleForTesting
  void parseVideoObjectLayer(int nalTypeOffset) {
    @NonNull final ParsableNalUnitBitArray in = new ParsableNalUnitBitArray(buffer, nalTypeOffset + 1, pos);
    in.skipBit(); // random_accessible_vol
    in.skipBits(8); // video_object_type_indication
    boolean is_object_layer_identifier = in.readBit();
    int video_object_layer_verid = 0;
    if (is_object_layer_identifier) {
      video_object_layer_verid = in.readBits(4);
      in.skipBits(3); // video_object_layer_priority
    }
    int aspect_ratio_info = in.readBits(4);
    final float aspectRatio;
    if (aspect_ratio_info == Extended_PAR) {
      float par_width = (float)in.readBits(8);
      float par_height = (float)in.readBits(8);
      aspectRatio = par_width / par_height;
    } else {
      aspectRatio = ASPECT_RATIO[aspect_ratio_info];
    }
    if (aspectRatio != pixelWidthHeightRatio) {
      trackOutput.format(formatBuilder.setPixelWidthHeightRatio(aspectRatio).build());
      pixelWidthHeightRatio = aspectRatio;
    }
    //vol_control_parameters
    if (in.readBit()) {
      in.skipBits(2 + 1); //chroma_format, low_delay
      //vbv_parameters
      if (in.readBit()) {
        in.skipBits(15+1 +15+1 +1+3+11+1 +15+1); //first_half_bit_rate...
      }
    }
    int video_object_layer_shape = in.readBits(2);
    if (video_object_layer_shape == SHAPE_TYPE_GRAYSCALE && video_object_layer_verid != 1) {
      in.skipBits(4); //video_object_layer_shape_extension
    }
    readMarkerBit(in);
    int vop_time_increment_resolution = in.readBits(16);
    vopTimeIncrementBits = (int)((Math.log(vop_time_increment_resolution) / Math.log(2))) + 1;
    setMaxPicCount(vop_time_increment_resolution , 1);
  }

  void parseVideoObjectPlane(int nalTypeOffset) {
    final ParsableNalUnitBitArray in = new ParsableNalUnitBitArray(buffer, nalTypeOffset + 1, buffer.length);
    in.skipBits(2); //vop_coding_type - 0 = I, 1 = P, 2 = B
    //modulo_time_base;
    while (in.readBit()) {}
    readMarkerBit(in);
    int vop_time_increment = in.readBits(vopTimeIncrementBits);
    setPicCount(vop_time_increment);
  }

  @Override
  void processChunk(ExtractorInput input, int nalTypeOffset) throws IOException {
    while (true) {
      final byte nalType = buffer[nalTypeOffset];
      if (usePicClock && nalType == VOP_START_CODE) {
        parseVideoObjectPlane(nalTypeOffset);
        break;
      } else if (nalType == SEQUENCE_START_CODE) {
        final byte profile_and_level_indication = buffer[nalTypeOffset + 1];
        usePicClock = (profile_and_level_indication & SIMPLE_PROFILE_MASK) != profile_and_level_indication;
      } else if ((nalType & 0xf0) == LAYER_START_CODE) {
        seekNextNal(input, nalTypeOffset);
        parseVideoObjectLayer(nalTypeOffset);
        // There may be a VOP start code after this NAL, so if we are tracking B frames, don't exit
        if (!usePicClock) {
          break;
        }
      }

      nalTypeOffset = seekNextNal(input, nalTypeOffset);
      if (nalTypeOffset < 0) {
        break;
      }
      compact();
    }
  }
}