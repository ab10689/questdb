/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2023 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cutlass.text.types;

import io.questdb.cairo.ColumnType;
import io.questdb.cairo.TableWriter;
import io.questdb.std.NumericException;
import io.questdb.std.Uuid;
import io.questdb.std.str.DirectByteCharSequence;

public final class UuidAdapter extends AbstractTypeAdapter {
    public static final UuidAdapter INSTANCE = new UuidAdapter();

    private UuidAdapter() {
    }

    @Override
    public int getType() {
        return ColumnType.UUID;
    }

    @Override
    public boolean probe(DirectByteCharSequence text) {
        try {
            Uuid.checkDashesAndLength(text);
            Uuid.parseHi(text);
            Uuid.parseLo(text);
        } catch (NumericException e) {
            return false;
        }
        return true;
    }

    @Override
    public void write(TableWriter.Row row, int column, DirectByteCharSequence value) {
        row.putUuid(column, value);
    }
}
