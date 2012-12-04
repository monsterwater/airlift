/*
 * Copyright 2004-present Facebook. All Rights Reserved.
 */
package com.facebook.presto.operator;

import com.facebook.presto.block.Block;
import com.facebook.presto.block.BlockBuilder;
import com.facebook.presto.block.BlockCursor;
import com.facebook.presto.tuple.TupleInfo;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;

import java.util.Iterator;
import java.util.List;

import static com.google.common.base.Preconditions.checkState;

public class FilterAndProjectOperator implements Operator
{
    private final Operator source;
    private final FilterFunction filter;
    private final List<ProjectionFunction> projections;
    private final List<TupleInfo> tupleInfos;

    public FilterAndProjectOperator(Operator source, FilterFunction filter, ProjectionFunction... projections)
    {
        this(source, filter, ImmutableList.copyOf(projections));
    }

    public FilterAndProjectOperator(Operator source, FilterFunction filter, List<ProjectionFunction> projections)
    {
        this.source = source;
        this.filter = filter;
        this.projections = ImmutableList.copyOf(projections);

        ImmutableList.Builder<TupleInfo> tupleInfos = ImmutableList.builder();
        for (ProjectionFunction projection : projections) {
            tupleInfos.add(projection.getTupleInfo());
        }
        this.tupleInfos = tupleInfos.build();
    }

    @Override
    public int getChannelCount()
    {
        return projections.size();
    }

    @Override
    public List<TupleInfo> getTupleInfos()
    {
        return tupleInfos;
    }

    @Override
    public Iterator<Page> iterator()
    {
        return new FilterAndProjectIterator(source.iterator(), filter, projections);
    }

    private static class FilterAndProjectIterator extends AbstractIterator<Page>
    {
        private final Iterator<Page> pageIterator;
        private final FilterFunction filterFunction;
        private final List<ProjectionFunction> projections;

        private long outputPosition;

        public FilterAndProjectIterator(Iterator<Page> pageIterator, FilterFunction filterFunction, List<ProjectionFunction> projections)
        {
            this.pageIterator = pageIterator;
            this.filterFunction = filterFunction;
            this.projections = projections;
        }

        protected Page computeNext()
        {
            BlockBuilder[] outputs = new BlockBuilder[projections.size()];
            for (int i = 0; i < outputs.length; i++) {
                outputs[i] = new BlockBuilder(projections.get(i).getTupleInfo());

            }

            while (!isFull(outputs) && pageIterator.hasNext()) {
                Page page = pageIterator.next();
                Block[] blocks = page.getBlocks();
                filterAndProjectRowOriented(blocks, outputs);
            }

            if (outputs[0].isEmpty()) {
                return endOfData();
            }

            Block[] blocks = new Block[projections.size()];
            for (int i = 0; i < blocks.length; i++) {
                blocks[i] = outputs[i].build();
            }

            Page page = new Page(blocks);
            outputPosition += blocks[0].getPositionCount();
            return page;
        }

        private boolean isFull(BlockBuilder... outputs)
        {
            for (BlockBuilder output : outputs) {
                if (output.isFull()) {
                    return true;
                }
            }
            return false;
        }

        private void filterAndProjectRowOriented(Block[] blocks, BlockBuilder[] outputs)
        {
            int rows = blocks[0].getPositionCount();

            BlockCursor[] cursors = new BlockCursor[blocks.length];
            for (int i = 0; i < blocks.length; i++) {
                cursors[i] = blocks[i].cursor();
            }

            for (int position = 0; position < rows; position++) {
                for (BlockCursor cursor : cursors) {
                    checkState(cursor.advanceNextPosition());
                }

                if (filterFunction.filter(cursors)) {
                    for (int i = 0; i < projections.size(); i++) {
                        projections.get(i).project(cursors, outputs[i]);
                    }
                }
            }

            for (BlockCursor cursor : cursors) {
                checkState(!cursor.advanceNextPosition());
            }
        }
    }
}