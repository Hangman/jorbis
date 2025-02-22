/* -*-mode:java; c-basic-offset:2; indent-tabs-mode:nil -*- */
/* -*-mode:java; c-basic-offset:2; indent-tabs-mode:nil -*- */
/* JOrbis Copyright (C) 2000 ymnk, JCraft,Inc.
 *
 * Written by: 2000 ymnk<ymnk@jcraft.com>
 *
 * Many thanks to Monty <monty@xiph.org> and The XIPHOPHORUS Company http://www.xiph.org/ . JOrbis has been based on their awesome works, Vorbis codec.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU Library General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public License along with this program; if not, write to the Free Software Foundation, Inc., 675
 * Mass Ave, Cambridge, MA 02139, USA. */

package de.pottgames.com.jcraft.jorbis;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import de.pottgames.com.jcraft.jogg.Packet;
import de.pottgames.com.jcraft.jogg.Page;
import de.pottgames.com.jcraft.jogg.StreamState;
import de.pottgames.com.jcraft.jogg.SyncState;

public class VorbisFile {
    static final int CHUNKSIZE = 8500;
    static final int SEEK_SET  = 0;
    static final int SEEK_CUR  = 1;
    static final int SEEK_END  = 2;

    static final int OV_FALSE = -1;
    static final int OV_EOF   = -2;
    static final int OV_HOLE  = -3;

    static final int OV_EREAD      = -128;
    static final int OV_EFAULT     = -129;
    static final int OV_EIMPL      = -130;
    static final int OV_EINVAL     = -131;
    static final int OV_ENOTVORBIS = -132;
    static final int OV_EBADHEADER = -133;
    static final int OV_EVERSION   = -134;
    static final int OV_ENOTAUDIO  = -135;
    static final int OV_EBADPACKET = -136;
    static final int OV_EBADLINK   = -137;
    static final int OV_ENOSEEK    = -138;

    InputStream datasource;
    boolean     seekable = false;
    long        offset;
    long        end;

    SyncState oy = new SyncState();

    int       links;
    long[]    offsets;
    long[]    dataoffsets;
    int[]     serialnos;
    long[]    pcmlengths;
    Info[]    vi;
    Comment[] vc;

    // Decoding working state local storage
    long    pcm_offset;
    boolean decode_ready = false;

    int current_serialno;
    int current_link;

    float bittrack;
    float samptrack;

    StreamState os = new StreamState(); // take physical pages, weld into a logical
    // stream of packets
    DspState vd = new DspState(); // central working state for
    // the packet->PCM decoder
    Block vb = new Block(this.vd); // local working space for packet->PCM decode

    // ov_callbacks callbacks;


    public VorbisFile(String file) throws JOrbisException {
        InputStream is = null;
        try {
            is = new SeekableInputStream(file);
            final int ret = this.open(is, null, 0);
            if (ret == -1) {
                throw new JOrbisException("VorbisFile: open return -1");
            }
        } catch (final Exception e) {
            throw new JOrbisException("VorbisFile: " + e.toString());
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (final IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }


    public VorbisFile(File file) throws JOrbisException {
        InputStream is = null;
        try {
            is = new SeekableInputStream(file);
            final int ret = this.open(is, null, 0);
            if (ret == -1) {
                throw new JOrbisException("VorbisFile: open return -1");
            }
        } catch (final Exception e) {
            throw new JOrbisException("VorbisFile: " + e.toString());
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (final IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }


    public VorbisFile(InputStream is, byte[] initial, int ibytes) throws JOrbisException {
        final int ret = this.open(is, initial, ibytes);
        if (ret == -1) {
        }
    }


    private int get_data() {
        final int index = this.oy.buffer(VorbisFile.CHUNKSIZE);
        final byte[] buffer = this.oy.data;
        int bytes = 0;
        try {
            bytes = this.datasource.read(buffer, index, VorbisFile.CHUNKSIZE);
        } catch (final Exception e) {
            return VorbisFile.OV_EREAD;
        }
        this.oy.wrote(bytes);
        if (bytes == -1) {
            bytes = 0;
        }
        return bytes;
    }


    private void seek_helper(long offst) {
        VorbisFile.fseek(this.datasource, offst, VorbisFile.SEEK_SET);
        this.offset = offst;
        this.oy.reset();
    }


    private int get_next_page(Page page, long boundary) {
        if (boundary > 0) {
            boundary += this.offset;
        }
        while (true) {
            int more;
            if (boundary > 0 && this.offset >= boundary) {
                return VorbisFile.OV_FALSE;
            }
            more = this.oy.pageseek(page);
            if (more < 0) {
                this.offset -= more;
            } else if (more == 0) {
                if (boundary == 0) {
                    return VorbisFile.OV_FALSE;
                }
                final int ret = this.get_data();
                if (ret == 0) {
                    return VorbisFile.OV_EOF;
                }
                if (ret < 0) {
                    return VorbisFile.OV_EREAD;
                }
            } else {
                final int ret = (int) this.offset; // !!!
                this.offset += more;
                return ret;
            }
        }
    }


    private int get_prev_page(Page page) throws JOrbisException {
        long begin = this.offset; // !!!
        int ret;
        int offst = -1;
        while (offst == -1) {
            begin -= VorbisFile.CHUNKSIZE;
            if (begin < 0) {
                begin = 0;
            }
            this.seek_helper(begin);
            while (this.offset < begin + VorbisFile.CHUNKSIZE) {
                ret = this.get_next_page(page, begin + VorbisFile.CHUNKSIZE - this.offset);
                if (ret == VorbisFile.OV_EREAD) {
                    return VorbisFile.OV_EREAD;
                }
                if (ret < 0) {
                    if (offst == -1) {
                        throw new JOrbisException();
                    }
                    break;
                }
                offst = ret;
            }
        }
        this.seek_helper(offst); // !!!
        ret = this.get_next_page(page, VorbisFile.CHUNKSIZE);
        if (ret < 0) {
            return VorbisFile.OV_EFAULT;
        }
        return offst;
    }


    int bisect_forward_serialno(long begin, long searched, long end, int currentno, int m) {
        long endsearched = end;
        long next = end;
        final Page page = new Page();
        int ret;

        while (searched < endsearched) {
            long bisect;
            if (endsearched - searched < VorbisFile.CHUNKSIZE) {
                bisect = searched;
            } else {
                bisect = (searched + endsearched) / 2;
            }

            this.seek_helper(bisect);
            ret = this.get_next_page(page, -1);
            if (ret == VorbisFile.OV_EREAD) {
                return VorbisFile.OV_EREAD;
            }
            if (ret < 0 || page.serialno() != currentno) {
                endsearched = bisect;
                if (ret >= 0) {
                    next = ret;
                }
            } else {
                searched = ret + page.header_len + page.body_len;
            }
        }
        this.seek_helper(next);
        ret = this.get_next_page(page, -1);
        if (ret == VorbisFile.OV_EREAD) {
            return VorbisFile.OV_EREAD;
        }

        if (searched >= end || ret == -1) {
            this.links = m + 1;
            this.offsets = new long[m + 2];
            this.offsets[m + 1] = searched;
        } else {
            ret = this.bisect_forward_serialno(next, this.offset, end, page.serialno(), m + 1);
            if (ret == VorbisFile.OV_EREAD) {
                return VorbisFile.OV_EREAD;
            }
        }
        this.offsets[m] = begin;
        return 0;
    }


    // uses the local ogg_stream storage in vf; this is important for
    // non-streaming input sources
    int fetch_headers(Info vi, Comment vc, int[] serialno, Page og_ptr) {
        final Page og = new Page();
        final Packet op = new Packet();
        int ret;

        if (og_ptr == null) {
            ret = this.get_next_page(og, VorbisFile.CHUNKSIZE);
            if (ret == VorbisFile.OV_EREAD) {
                return VorbisFile.OV_EREAD;
            }
            if (ret < 0) {
                return VorbisFile.OV_ENOTVORBIS;
            }
            og_ptr = og;
        }

        if (serialno != null) {
            serialno[0] = og_ptr.serialno();
        }

        this.os.init(og_ptr.serialno());

        // extract the initial header from the first page and verify that the
        // Ogg bitstream is in fact Vorbis data

        vi.init();
        vc.init();

        int i = 0;
        while (i < 3) {
            this.os.pagein(og_ptr);
            while (i < 3) {
                final int result = this.os.packetout(op);
                if (result == 0) {
                    break;
                }
                if (result == -1) {
                    vi.clear();
                    vc.clear();
                    this.os.clear();
                    return -1;
                }
                if (vi.synthesis_headerin(vc, op) != 0) {
                    vi.clear();
                    vc.clear();
                    this.os.clear();
                    return -1;
                }
                i++;
            }
            if (i < 3) {
                if (this.get_next_page(og_ptr, 1) < 0) {
                    vi.clear();
                    vc.clear();
                    this.os.clear();
                    return -1;
                }
            }
        }
        return 0;
    }


    // last step of the OggVorbis_File initialization; get all the
    // vorbis_info structs and PCM positions. Only called by the seekable
    // initialization (local stream storage is hacked slightly; pay
    // attention to how that's done)
    void prefetch_all_headers(Info first_i, Comment first_c, int dataoffset) throws JOrbisException {
        final Page og = new Page();
        int ret;

        this.vi = new Info[this.links];
        this.vc = new Comment[this.links];
        this.dataoffsets = new long[this.links];
        this.pcmlengths = new long[this.links];
        this.serialnos = new int[this.links];

        for (int i = 0; i < this.links; i++) {
            if (first_i != null && first_c != null && i == 0) {
                // we already grabbed the initial header earlier. This just
                // saves the waste of grabbing it again
                this.vi[i] = first_i;
                this.vc[i] = first_c;
                this.dataoffsets[i] = dataoffset;
            } else {
                // seek to the location of the initial header
                this.seek_helper(this.offsets[i]); // !!!
                this.vi[i] = new Info();
                this.vc[i] = new Comment();
                if (this.fetch_headers(this.vi[i], this.vc[i], null, null) == -1) {
                    this.dataoffsets[i] = -1;
                } else {
                    this.dataoffsets[i] = this.offset;
                    this.os.clear();
                }
            }

            // get the serial number and PCM length of this link. To do this,
            // get the last page of the stream
            {
                final long end = this.offsets[i + 1]; // !!!
                this.seek_helper(end);

                while (true) {
                    ret = this.get_prev_page(og);
                    if (ret == -1) {
                        // this should not be possible
                        this.vi[i].clear();
                        this.vc[i].clear();
                        break;
                    }
                    if (og.granulepos() != -1) {
                        this.serialnos[i] = og.serialno();
                        this.pcmlengths[i] = og.granulepos();
                        break;
                    }
                }
            }
        }
    }


    private int make_decode_ready() {
        if (this.decode_ready) {
            System.exit(1);
        }
        this.vd.synthesis_init(this.vi[0]);
        this.vb.init(this.vd);
        this.decode_ready = true;
        return 0;
    }


    int open_seekable() throws JOrbisException {
        final Info initial_i = new Info();
        final Comment initial_c = new Comment();
        int serialno;
        long end;
        int ret;
        int dataoffset;
        final Page og = new Page();
        // is this even vorbis...?
        final int[] foo = new int[1];
        ret = this.fetch_headers(initial_i, initial_c, foo, null);
        serialno = foo[0];
        dataoffset = (int) this.offset; // !!
        this.os.clear();
        if (ret == -1) {
            return -1;
        }
        if (ret < 0) {
            return ret;
        }
        // we can seek, so set out learning all about this file
        this.seekable = true;
        VorbisFile.fseek(this.datasource, 0, VorbisFile.SEEK_END);
        this.offset = VorbisFile.ftell(this.datasource);
        end = this.offset;
        // We get the offset for the last page of the physical bitstream.
        // Most OggVorbis files will contain a single logical bitstream
        end = this.get_prev_page(og);
        // moer than one logical bitstream?
        if (og.serialno() != serialno) {
            // Chained bitstream. Bisect-search each logical bitstream
            // section. Do so based on serial number only
            if (this.bisect_forward_serialno(0, 0, end + 1, serialno, 0) < 0) {
                this.clear();
                return VorbisFile.OV_EREAD;
            }
        } else // Only one logical bitstream
        if (this.bisect_forward_serialno(0, end, end + 1, serialno, 0) < 0) {
            this.clear();
            return VorbisFile.OV_EREAD;
        }
        this.prefetch_all_headers(initial_i, initial_c, dataoffset);
        return 0;
    }


    int open_nonseekable() {
        // we cannot seek. Set up a 'single' (current) logical bitstream entry
        this.links = 1;
        this.vi = new Info[this.links];
        this.vi[0] = new Info(); // ??
        this.vc = new Comment[this.links];
        this.vc[0] = new Comment(); // ?? bug?

        // Try to fetch the headers, maintaining all the storage
        final int[] foo = new int[1];
        if (this.fetch_headers(this.vi[0], this.vc[0], foo, null) == -1) {
            return -1;
        }
        this.current_serialno = foo[0];
        this.make_decode_ready();
        return 0;
    }


    // clear out the current logical bitstream decoder
    void decode_clear() {
        this.os.clear();
        this.vd.clear();
        this.vb.clear();
        this.decode_ready = false;
        this.bittrack = 0.f;
        this.samptrack = 0.f;
    }

    // fetch and process a packet. Handles the case where we're at a
    // bitstream boundary and dumps the decoding machine. If the decoding
    // machine is unloaded, it loads it. It also keeps pcm_offset up to
    // date (seek and read both use this. seek uses a special hack with
    // readp).
    //
    // return: -1) hole in the data (lost packet)
    // 0) need more date (only if readp==0)/eof
    // 1) got a packet


    int process_packet(int readp) {
        final Page og = new Page();

        // handle one packet. Try to fetch it from current stream state
        // extract packets from page
        while (true) {
            // process a packet if we can. If the machine isn't loaded,
            // neither is a page
            if (this.decode_ready) {
                final Packet op = new Packet();
                final int result = this.os.packetout(op);
                long granulepos;
                // if(result==-1)return(-1); // hole in the data. For now, swallow
                // and go. We'll need to add a real
                // error code in a bit.
                if (result > 0) {
                    // got a packet. process it
                    granulepos = op.granulepos;
                    if (this.vb.synthesis(op) == 0) { // lazy check for lazy
                        // header handling. The
                        // header packets aren't
                        // audio, so if/when we
                        // submit them,
                        // vorbis_synthesis will
                        // reject them
                        // suck in the synthesis data and track bitrate
                        {
                            final int oldsamples = this.vd.synthesis_pcmout(null, null);
                            this.vd.synthesis_blockin(this.vb);
                            this.samptrack += this.vd.synthesis_pcmout(null, null) - oldsamples;
                            this.bittrack += op.bytes * 8;
                        }

                        // update the pcm offset.
                        if (granulepos != -1 && op.e_o_s == 0) {
                            final int link = this.seekable ? this.current_link : 0;
                            int samples;
                            // this packet has a pcm_offset on it (the last packet
                            // completed on a page carries the offset) After processing
                            // (above), we know the pcm position of the *last* sample
                            // ready to be returned. Find the offset of the *first*
                            //
                            // As an aside, this trick is inaccurate if we begin
                            // reading anew right at the last page; the end-of-stream
                            // granulepos declares the last frame in the stream, and the
                            // last packet of the last page may be a partial frame.
                            // So, we need a previous granulepos from an in-sequence page
                            // to have a reference point. Thus the !op.e_o_s clause above

                            samples = this.vd.synthesis_pcmout(null, null);
                            granulepos -= samples;
                            for (int i = 0; i < link; i++) {
                                granulepos += this.pcmlengths[i];
                            }
                            this.pcm_offset = granulepos;
                        }
                        return 1;
                    }
                }
            }

            if (readp == 0) {
                return 0;
            }
            if (this.get_next_page(og, -1) < 0) {
                return 0; // eof. leave unitialized
            }

            // bitrate tracking; add the header's bytes here, the body bytes
            // are done by packet above
            this.bittrack += og.header_len * 8;

            // has our decoding just traversed a bitstream boundary?
            if (this.decode_ready) {
                if (this.current_serialno != og.serialno()) {
                    this.decode_clear();
                }
            }

            // Do we need to load a new machine before submitting the page?
            // This is different in the seekable and non-seekable cases.
            //
            // In the seekable case, we already have all the header
            // information loaded and cached; we just initialize the machine
            // with it and continue on our merry way.
            //
            // In the non-seekable (streaming) case, we'll only be at a
            // boundary if we just left the previous logical bitstream and
            // we're now nominally at the header of the next bitstream

            if (!this.decode_ready) {
                int i;
                if (this.seekable) {
                    this.current_serialno = og.serialno();

                    // match the serialno to bitstream section. We use this rather than
                    // offset positions to avoid problems near logical bitstream
                    // boundaries
                    for (i = 0; i < this.links; i++) {
                        if (this.serialnos[i] == this.current_serialno) {
                            break;
                        }
                    }
                    if (i == this.links) {
                        return -1; // sign of a bogus stream. error out,
                    }
                    // leave machine uninitialized
                    this.current_link = i;

                    this.os.init(this.current_serialno);
                    this.os.reset();

                } else {
                    // we're streaming
                    // fetch the three header packets, build the info struct
                    final int foo[] = new int[1];
                    final int ret = this.fetch_headers(this.vi[0], this.vc[0], foo, og);
                    this.current_serialno = foo[0];
                    if (ret != 0) {
                        return ret;
                    }
                    this.current_link++;
                    i = 0;
                }
                this.make_decode_ready();
            }
            this.os.pagein(og);
        }
    }


    // The helpers are over; it's all toplevel interface from here on out
    // clear out the OggVorbis_File struct
    int clear() {
        this.vb.clear();
        this.vd.clear();
        this.os.clear();

        if (this.vi != null && this.links != 0) {
            for (int i = 0; i < this.links; i++) {
                this.vi[i].clear();
                this.vc[i].clear();
            }
            this.vi = null;
            this.vc = null;
        }
        if (this.dataoffsets != null) {
            this.dataoffsets = null;
        }
        if (this.pcmlengths != null) {
            this.pcmlengths = null;
        }
        if (this.serialnos != null) {
            this.serialnos = null;
        }
        if (this.offsets != null) {
            this.offsets = null;
        }
        this.oy.clear();

        return 0;
    }


    static int fseek(InputStream fis, long off, int whence) {
        if (fis instanceof SeekableInputStream) {
            final SeekableInputStream sis = (SeekableInputStream) fis;
            try {
                if (whence == VorbisFile.SEEK_SET) {
                    sis.seek(off);
                } else if (whence == VorbisFile.SEEK_END) {
                    sis.seek(sis.getLength() - off);
                } else {
                }
            } catch (final Exception e) {
            }
            return 0;
        }
        try {
            if (whence == 0) {
                fis.reset();
            }
            fis.skip(off);
        } catch (final Exception e) {
            return -1;
        }
        return 0;
    }


    static long ftell(InputStream fis) {
        try {
            if (fis instanceof SeekableInputStream) {
                return ((SeekableInputStream) fis).tell();
            }
        } catch (final Exception e) {
        }
        return 0;
    }

    // inspects the OggVorbis file and finds/documents all the logical
    // bitstreams contained in it. Tries to be tolerant of logical
    // bitstream sections that are truncated/woogie.
    //
    // return: -1) error
    // 0) OK


    int open(InputStream is, byte[] initial, int ibytes) throws JOrbisException {
        return this.open_callbacks(is, initial, ibytes);
    }


    int open_callbacks(InputStream is, byte[] initial, int ibytes// , callbacks callbacks
    ) throws JOrbisException {
        int ret;
        this.datasource = is;

        this.oy.init();

        // perhaps some data was previously read into a buffer for testing
        // against other stream types. Allow initialization from this
        // previously read data (as we may be reading from a non-seekable
        // stream)
        if (initial != null) {
            final int index = this.oy.buffer(ibytes);
            System.arraycopy(initial, 0, this.oy.data, index, ibytes);
            this.oy.wrote(ibytes);
        }
        // can we seek? Stevens suggests the seek test was portable
        if (is instanceof SeekableInputStream) {
            ret = this.open_seekable();
        } else {
            ret = this.open_nonseekable();
        }
        if (ret != 0) {
            this.datasource = null;
            this.clear();
        }
        return ret;
    }


    // How many logical bitstreams in this physical bitstream?
    public int streams() {
        return this.links;
    }


    // Is the FILE * associated with vf seekable?
    public boolean seekable() {
        return this.seekable;
    }

    // returns the bitrate for a given logical bitstream or the entire
    // physical bitstream. If the file is open for random access, it will
    // find the *actual* average bitrate. If the file is streaming, it
    // returns the nominal bitrate (if set) else the average of the
    // upper/lower bounds (if set) else -1 (unset).
    //
    // If you want the actual bitrate field settings, get them from the
    // vorbis_info structs


    public int bitrate(int i) {
        if (i >= this.links) {
            return -1;
        }
        if (!this.seekable && i != 0) {
            return this.bitrate(0);
        }
        if (i < 0) {
            long bits = 0;
            for (int j = 0; j < this.links; j++) {
                bits += (this.offsets[j + 1] - this.dataoffsets[j]) * 8;
            }
            return (int) Math.rint(bits / this.time_total(-1));
        }
        if (this.seekable) {
            // return the actual bitrate
            return (int) Math.rint((this.offsets[i + 1] - this.dataoffsets[i]) * 8 / this.time_total(i));
        }
        // return nominal if set
        if (this.vi[i].bitrate_nominal > 0) {
            return this.vi[i].bitrate_nominal;
        }
        if (this.vi[i].bitrate_upper > 0) {
            if (this.vi[i].bitrate_lower > 0) {
                return (this.vi[i].bitrate_upper + this.vi[i].bitrate_lower) / 2;
            }
            return this.vi[i].bitrate_upper;
        }
        return -1;
    }


    // returns the actual bitrate since last call. returns -1 if no
    // additional data to offer since last call (or at beginning of stream)
    public int bitrate_instant() {
        final int _link = this.seekable ? this.current_link : 0;
        if (this.samptrack == 0) {
            return -1;
        }
        final int ret = (int) (this.bittrack / this.samptrack * this.vi[_link].rate + .5);
        this.bittrack = 0.f;
        this.samptrack = 0.f;
        return ret;
    }


    public int serialnumber(int i) {
        if (i >= this.links) {
            return -1;
        }
        if (!this.seekable && i >= 0) {
            return this.serialnumber(-1);
        }
        if (i < 0) {
            return this.current_serialno;
        }
        return this.serialnos[i];
    }

    // returns: total raw (compressed) length of content if i==-1
    // raw (compressed) length of that logical bitstream for i==0 to n
    // -1 if the stream is not seekable (we can't know the length)


    public long raw_total(int i) {
        if (!this.seekable || i >= this.links) {
            return -1;
        }
        if (i >= 0) {
            return this.offsets[i + 1] - this.offsets[i];
        }
        long acc = 0; // bug?
        for (int j = 0; j < this.links; j++) {
            acc += this.raw_total(j);
        }
        return acc;
    }


    // returns: total PCM length (samples) of content if i==-1
    // PCM length (samples) of that logical bitstream for i==0 to n
    // -1 if the stream is not seekable (we can't know the length)
    public long pcm_total(int i) {
        if (!this.seekable || i >= this.links) {
            return -1;
        }
        if (i >= 0) {
            return this.pcmlengths[i];
        }
        long acc = 0;
        for (int j = 0; j < this.links; j++) {
            acc += this.pcm_total(j);
        }
        return acc;
    }


    // returns: total seconds of content if i==-1
    // seconds in that logical bitstream for i==0 to n
    // -1 if the stream is not seekable (we can't know the length)
    public float time_total(int i) {
        if (!this.seekable || i >= this.links) {
            return -1;
        }
        if (i >= 0) {
            return (float) this.pcmlengths[i] / this.vi[i].rate;
        }
        float acc = 0;
        for (int j = 0; j < this.links; j++) {
            acc += this.time_total(j);
        }
        return acc;
    }

    // seek to an offset relative to the *compressed* data. This also
    // immediately sucks in and decodes pages to update the PCM cursor. It
    // will cross a logical bitstream boundary, but only if it can't get
    // any packets out of the tail of the bitstream we seek to (so no
    // surprises).
    //
    // returns zero on success, nonzero on failure


    public int raw_seek(int pos) {
        if (!this.seekable) {
            return -1; // don't dump machine if we can't seek
        }
        if (pos < 0 || pos > this.offsets[this.links]) {
            // goto seek_error;
            this.pcm_offset = -1;
            this.decode_clear();
            return -1;
        }

        // clear out decoding machine state
        this.pcm_offset = -1;
        this.decode_clear();

        // seek
        this.seek_helper(pos);

        // we need to make sure the pcm_offset is set. We use the
        // _fetch_packet helper to process one packet with readp set, then
        // call it until it returns '0' with readp not set (the last packet
        // from a page has the 'granulepos' field set, and that's how the
        // helper updates the offset

        switch (this.process_packet(1)) {
            case 0:
                // oh, eof. There are no packets remaining. Set the pcm offset to
                // the end of file
                this.pcm_offset = this.pcm_total(-1);
                return 0;
            case -1:
                // error! missing data or invalid bitstream structure
                // goto seek_error;
                this.pcm_offset = -1;
                this.decode_clear();
                return -1;
            default:
                // all OK
                break;
        }
        while (true) {
            switch (this.process_packet(0)) {
                case 0:
                    // the offset is set. If it's a bogus bitstream with no offset
                    // information, it's not but that's not our fault. We still run
                    // gracefully, we're just missing the offset
                    return 0;
                case -1:
                    // error! missing data or invalid bitstream structure
                    // goto seek_error;
                    this.pcm_offset = -1;
                    this.decode_clear();
                    return -1;
                default:
                    // continue processing packets
                    break;
            }
        }

        // seek_error:
        // dump the machine so we're in a known state
        // pcm_offset=-1;
        // decode_clear();
        // return -1;
    }

    // seek to a sample offset relative to the decompressed pcm stream
    // returns zero on success, nonzero on failure


    public int pcm_seek(long pos) {
        int link = -1;
        long total = this.pcm_total(-1);

        if (!this.seekable) {
            return -1; // don't dump machine if we can't seek
        }
        if (pos < 0 || pos > total) {
            // goto seek_error;
            this.pcm_offset = -1;
            this.decode_clear();
            return -1;
        }

        // which bitstream section does this pcm offset occur in?
        for (link = this.links - 1; link >= 0; link--) {
            total -= this.pcmlengths[link];
            if (pos >= total) {
                break;
            }
        }

        // search within the logical bitstream for the page with the highest
        // pcm_pos preceeding (or equal to) pos. There is a danger here;
        // missing pages or incorrect frame number information in the
        // bitstream could make our task impossible. Account for that (it
        // would be an error condition)
        {
            final long target = pos - total;
            long end = this.offsets[link + 1];
            long begin = this.offsets[link];
            int best = (int) begin;

            final Page og = new Page();
            while (begin < end) {
                long bisect;
                int ret;

                if (end - begin < VorbisFile.CHUNKSIZE) {
                    bisect = begin;
                } else {
                    bisect = (end + begin) / 2;
                }

                this.seek_helper(bisect);
                ret = this.get_next_page(og, end - bisect);

                if (ret == -1) {
                    end = bisect;
                } else {
                    final long granulepos = og.granulepos();
                    if (granulepos < target) {
                        best = ret; // raw offset of packet with granulepos
                        begin = this.offset; // raw offset of next packet
                    } else {
                        end = bisect;
                    }
                }
            }
            // found our page. seek to it (call raw_seek).
            if (this.raw_seek(best) != 0) {
                // goto seek_error;
                this.pcm_offset = -1;
                this.decode_clear();
                return -1;
            }
        }

        // verify result
        if (this.pcm_offset >= pos) {
            // goto seek_error;
            this.pcm_offset = -1;
            this.decode_clear();
            return -1;
        }
        if (pos > this.pcm_total(-1)) {
            // goto seek_error;
            this.pcm_offset = -1;
            this.decode_clear();
            return -1;
        }

        // discard samples until we reach the desired position. Crossing a
        // logical bitstream boundary with abandon is OK.
        while (this.pcm_offset < pos) {
            final int target = (int) (pos - this.pcm_offset);
            final float[][][] _pcm = new float[1][][];
            final int[] _index = new int[this.getInfo(-1).channels];
            int samples = this.vd.synthesis_pcmout(_pcm, _index);

            if (samples > target) {
                samples = target;
            }
            this.vd.synthesis_read(samples);
            this.pcm_offset += samples;

            if (samples < target) {
                if (this.process_packet(1) == 0) {
                    this.pcm_offset = this.pcm_total(-1); // eof
                }
            }
        }
        return 0;

        // seek_error:
        // dump machine so we're in a known state
        // pcm_offset=-1;
        // decode_clear();
        // return -1;
    }


    // seek to a playback time relative to the decompressed pcm stream
    // returns zero on success, nonzero on failure
    int time_seek(float seconds) {
        // translate time to PCM position and call pcm_seek

        int link = -1;
        long pcm_total = this.pcm_total(-1);
        float time_total = this.time_total(-1);

        if (!this.seekable) {
            return -1; // don't dump machine if we can't seek
        }
        if (seconds < 0 || seconds > time_total) {
            // goto seek_error;
            this.pcm_offset = -1;
            this.decode_clear();
            return -1;
        }

        // which bitstream section does this time offset occur in?
        for (link = this.links - 1; link >= 0; link--) {
            pcm_total -= this.pcmlengths[link];
            time_total -= this.time_total(link);
            if (seconds >= time_total) {
                break;
            }
        }

        // enough information to convert time offset to pcm offset
        {
            final long target = (long) (pcm_total + (seconds - time_total) * this.vi[link].rate);
            return this.pcm_seek(target);
        }

        // seek_error:
        // dump machine so we're in a known state
        // pcm_offset=-1;
        // decode_clear();
        // return -1;
    }


    // tell the current stream offset cursor. Note that seek followed by
    // tell will likely not give the set offset due to caching
    public long raw_tell() {
        return this.offset;
    }


    // return PCM offset (sample) of next PCM sample to be read
    public long pcm_tell() {
        return this.pcm_offset;
    }


    // return time offset (seconds) of next PCM sample to be read
    public float time_tell() {
        // translate time to PCM position and call pcm_seek

        int link = -1;
        long pcm_total = 0;
        float time_total = 0.f;

        if (this.seekable) {
            pcm_total = this.pcm_total(-1);
            time_total = this.time_total(-1);

            // which bitstream section does this time offset occur in?
            for (link = this.links - 1; link >= 0; link--) {
                pcm_total -= this.pcmlengths[link];
                time_total -= this.time_total(link);
                if (this.pcm_offset >= pcm_total) {
                    break;
                }
            }
        }

        return time_total + (float) (this.pcm_offset - pcm_total) / this.vi[link].rate;
    }

    // link: -1) return the vorbis_info struct for the bitstream section
    // currently being decoded
    // 0-n) to request information for a specific bitstream section
    //
    // In the case of a non-seekable bitstream, any call returns the
    // current bitstream. NULL in the case that the machine is not
    // initialized


    public Info getInfo(int link) {
        if (this.seekable) {
            if (link < 0) {
                if (this.decode_ready) {
                    return this.vi[this.current_link];
                }
                return null;
            }
            if (link >= this.links) {
                return null;
            }
            return this.vi[link];
        }
        if (this.decode_ready) {
            return this.vi[0];
        }
        return null;
    }


    public Comment getComment(int link) {
        if (this.seekable) {
            if (link < 0) {
                if (this.decode_ready) {
                    return this.vc[this.current_link];
                }
                return null;
            }
            if (link >= this.links) {
                return null;
            }
            return this.vc[link];
        }
        if (this.decode_ready) {
            return this.vc[0];
        }
        return null;
    }


    int host_is_big_endian() {
        return 1;
        // short pattern = 0xbabe;
        // unsigned char *bytewise = (unsigned char *)&pattern;
        // if (bytewise[0] == 0xba) return 1;
        // assert(bytewise[0] == 0xbe);
        // return 0;
    }

    // up to this point, everything could more or less hide the multiple
    // logical bitstream nature of chaining from the toplevel application
    // if the toplevel application didn't particularly care. However, at
    // the point that we actually read audio back, the multiple-section
    // nature must surface: Multiple bitstream sections do not necessarily
    // have to have the same number of channels or sampling rate.
    //
    // read returns the sequential logical bitstream number currently
    // being decoded along with the PCM data in order that the toplevel
    // application can take action on channel/sample rate changes. This
    // number will be incremented even for streamed (non-seekable) streams
    // (for seekable streams, it represents the actual logical bitstream
    // index within the physical bitstream. Note that the accessor
    // functions above are aware of this dichotomy).
    //
    // input values: buffer) a buffer to hold packed PCM data for return
    // length) the byte length requested to be placed into buffer
    // bigendianp) should the data be packed LSB first (0) or
    // MSB first (1)
    // word) word size for output. currently 1 (byte) or
    // 2 (16 bit short)
    //
    // return values: -1) error/hole in data
    // 0) EOF
    // n) number of bytes of PCM actually returned. The
    // below works on a packet-by-packet basis, so the
    // return length is not related to the 'length' passed
    // in, just guaranteed to fit.
    //
    // *section) set to the logical bitstream number


    int read(byte[] buffer, int length, int bigendianp, int word, int sgned, int[] bitstream) {
        final int host_endian = this.host_is_big_endian();
        int index = 0;

        while (true) {
            if (this.decode_ready) {
                float[][] pcm;
                final float[][][] _pcm = new float[1][][];
                final int[] _index = new int[this.getInfo(-1).channels];
                int samples = this.vd.synthesis_pcmout(_pcm, _index);
                pcm = _pcm[0];
                if (samples != 0) {
                    // yay! proceed to pack data into the byte buffer
                    final int channels = this.getInfo(-1).channels;
                    final int bytespersample = word * channels;
                    if (samples > length / bytespersample) {
                        samples = length / bytespersample;
                    }

                    // a tight loop to pack each size
                    {
                        int val;
                        if (word == 1) {
                            final int off = sgned != 0 ? 0 : 128;
                            for (int j = 0; j < samples; j++) {
                                for (int i = 0; i < channels; i++) {
                                    val = (int) (pcm[i][_index[i] + j] * 128. + 0.5);
                                    if (val > 127) {
                                        val = 127;
                                    } else if (val < -128) {
                                        val = -128;
                                    }
                                    buffer[index++] = (byte) (val + off);
                                }
                            }
                        } else {
                            final int off = sgned != 0 ? 0 : 32768;

                            if (host_endian == bigendianp) {
                                if (sgned != 0) {
                                    for (int i = 0; i < channels; i++) { // It's faster in this order
                                        final int src = _index[i];
                                        int dest = i;
                                        for (int j = 0; j < samples; j++) {
                                            val = (int) (pcm[i][src + j] * 32768. + 0.5);
                                            if (val > 32767) {
                                                val = 32767;
                                            } else if (val < -32768) {
                                                val = -32768;
                                            }
                                            buffer[dest] = (byte) (val >>> 8);
                                            buffer[dest + 1] = (byte) val;
                                            dest += channels * 2;
                                        }
                                    }
                                } else {
                                    for (int i = 0; i < channels; i++) {
                                        final float[] src = pcm[i];
                                        int dest = i;
                                        for (int j = 0; j < samples; j++) {
                                            val = (int) (src[j] * 32768. + 0.5);
                                            if (val > 32767) {
                                                val = 32767;
                                            } else if (val < -32768) {
                                                val = -32768;
                                            }
                                            buffer[dest] = (byte) (val + off >>> 8);
                                            buffer[dest + 1] = (byte) (val + off);
                                            dest += channels * 2;
                                        }
                                    }
                                }
                            } else if (bigendianp != 0) {
                                for (int j = 0; j < samples; j++) {
                                    for (int i = 0; i < channels; i++) {
                                        val = (int) (pcm[i][j] * 32768. + 0.5);
                                        if (val > 32767) {
                                            val = 32767;
                                        } else if (val < -32768) {
                                            val = -32768;
                                        }
                                        val += off;
                                        buffer[index++] = (byte) (val >>> 8);
                                        buffer[index++] = (byte) val;
                                    }
                                }
                            } else {
                                // int val;
                                for (int j = 0; j < samples; j++) {
                                    for (int i = 0; i < channels; i++) {
                                        val = (int) (pcm[i][j] * 32768. + 0.5);
                                        if (val > 32767) {
                                            val = 32767;
                                        } else if (val < -32768) {
                                            val = -32768;
                                        }
                                        val += off;
                                        buffer[index++] = (byte) val;
                                        buffer[index++] = (byte) (val >>> 8);
                                    }
                                }
                            }
                        }
                    }

                    this.vd.synthesis_read(samples);
                    this.pcm_offset += samples;
                    if (bitstream != null) {
                        bitstream[0] = this.current_link;
                    }
                    return samples * bytespersample;
                }
            }

            // suck in another packet
            switch (this.process_packet(1)) {
                case 0:
                    return 0;
                case -1:
                    return -1;
                default:
                    break;
            }
        }
    }


    public Info[] getInfo() {
        return this.vi;
    }


    public Comment[] getComment() {
        return this.vc;
    }


    public void close() throws java.io.IOException {
        this.datasource.close();
    }


    static class SeekableInputStream extends InputStream {
        java.io.RandomAccessFile raf  = null;
        final String             mode = "r";


        SeekableInputStream(String file) throws java.io.IOException {
            this.raf = new java.io.RandomAccessFile(file, this.mode);
        }


        SeekableInputStream(File file) throws java.io.IOException {
            this.raf = new java.io.RandomAccessFile(file, this.mode);
        }


        @Override
        public int read() throws java.io.IOException {
            return this.raf.read();
        }


        @Override
        public int read(byte[] buf) throws java.io.IOException {
            return this.raf.read(buf);
        }


        @Override
        public int read(byte[] buf, int s, int len) throws java.io.IOException {
            return this.raf.read(buf, s, len);
        }


        @Override
        public long skip(long n) throws java.io.IOException {
            return this.raf.skipBytes((int) n);
        }


        public long getLength() throws java.io.IOException {
            return this.raf.length();
        }


        public long tell() throws java.io.IOException {
            return this.raf.getFilePointer();
        }


        @Override
        public int available() throws java.io.IOException {
            return this.raf.length() == this.raf.getFilePointer() ? 0 : 1;
        }


        @Override
        public void close() throws java.io.IOException {
            this.raf.close();
        }


        @Override
        public synchronized void mark(int m) {
        }


        @Override
        public synchronized void reset() throws java.io.IOException {
        }


        @Override
        public boolean markSupported() {
            return false;
        }


        public void seek(long pos) throws java.io.IOException {
            this.raf.seek(pos);
        }
    }

}
