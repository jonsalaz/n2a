/*
Copyright 2018-2023 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/


#ifndef n2a_holder_tcc
#define n2a_holder_tcc


#include "math.h"
#include "holder.h"
#include "runtime.h"   // For Event::exponent
#include "image.h"

// In case windows.h is included...
#define WIN32_LEAN_AND_MEAN

#include <fstream>
#include <stdlib.h>
#include <time.h>
#include <sys/stat.h>
#ifdef _MSC_VER
#  define stat _stat
#  include <windows.h>
#  undef min
#  undef max
#else
#  include <dirent.h>
#endif

#ifdef HAVE_GL
#  include "glcorearb.h"  // Extensions
#  include <fstream>      // for loading shader programs
#  include <sstream>      // ditto
#  ifdef _WIN32
#    include <windows.h>
#    undef min
#    undef max
#    include "wglext.h"   // WGL -- Windows-specific functions for creating GL context
#  endif
#define GL_FUNCTIONS(X) \
   X(PFNGLGENVERTEXARRAYSPROC,          glGenVertexArrays          ) \
   X(PFNGLBINDVERTEXARRAYPROC,          glBindVertexArray          ) \
   X(PFNGLGENBUFFERSPROC,               glGenBuffers               ) \
   X(PFNGLBINDBUFFERPROC,               glBindBuffer               ) \
   X(PFNGLBUFFERDATAPROC,               glBufferData               ) \
   X(PFNGLVERTEXATTRIBPOINTERPROC,      glVertexAttribPointer      ) \
   X(PFNGLENABLEVERTEXATTRIBARRAYPROC,  glEnableVertexAttribArray  ) \
   X(PFNGLGENRENDERBUFFERSPROC,         glGenRenderbuffers         ) \
   X(PFNGLBINDRENDERBUFFERPROC,         glBindRenderbuffer         ) \
   X(PFNGLRENDERBUFFERSTORAGEPROC,      glRenderbufferStorage      ) \
   X(PFNGLGENFRAMEBUFFERSPROC,          glGenFramebuffers          ) \
   X(PFNGLBINDFRAMEBUFFERPROC,          glBindFramebuffer          ) \
   X(PFNGLFRAMEBUFFERRENDERBUFFERPROC,  glFramebufferRenderbuffer  ) \
   X(PFNGLDELETEFRAMEBUFFERSPROC,       glDeleteFramebuffers       ) \
   X(PFNGLDELETERENDERBUFFERSPROC,      glDeleteRenderbuffers      ) \
   X(PFNGLCREATESHADERPROC,             glCreateShader             ) \
   X(PFNGLSHADERSOURCEPROC,             glShaderSource             ) \
   X(PFNGLCOMPILESHADERPROC,            glCompileShader            ) \
   X(PFNGLGETSHADERIVPROC,              glGetShaderiv              ) \
   X(PFNGLGETSHADERINFOLOGPROC,         glGetShaderInfoLog         ) \
   X(PFNGLCREATEPROGRAMPROC,            glCreateProgram            ) \
   X(PFNGLATTACHSHADERPROC,             glAttachShader             ) \
   X(PFNGLLINKPROGRAMPROC,              glLinkProgram              ) \
   X(PFNGLUSEPROGRAMPROC,               glUseProgram               ) \
   X(PFNGLUNIFORMMATRIX4FVPROC,         glUniformMatrix4fv         ) \
   X(PFNGLGETUNIFORMLOCATIONPROC,       glGetUniformLocation       ) \
   X(PFNGLGETATTRIBLOCATIONPROC,        glGetAttribLocation        ) \
   X(PFNGLUNIFORM1IPROC,                glUniform1i                ) \
   X(PFNGLUNIFORM3FVPROC,               glUniform3fv               ) \
   X(PFNGLUNIFORM1FPROC,                glUniform1f                ) \
   X(PFNGLUNIFORM4FVPROC,               glUniform4fv               )
#  define X(type, name) static type name;
   GL_FUNCTIONS(X)
#  undef X
#endif


// class Parameters ----------------------------------------------------------

template<class T>
void
Parameters<T>::parse (const String & line)
{
    int pos = line.find_first_of ('=');
    if (pos == String::npos)
    {
        namedValues[line] = "";
    }
    else
    {
        String name  = line.substr (0, pos);
        String value = line.substr (pos + 1);
        if (name == "-include") read (value);
        else                    namedValues[name] = value;
    }
}

template<class T>
void
Parameters<T>::parse (int argc, const char * argv[])
{
    for (int i = 1; i < argc; i++) parse (argv[i]);
}

template<class T>
void
Parameters<T>::read (const String & parmFileName)
{
    std::ifstream ifs (parmFileName.c_str ());
    read (ifs);
}

template<class T>
void
Parameters<T>::read (std::istream & stream)
{
    while (stream.good ())
    {
        String line;
        getline (stream, line);
        line.trim ();
        parse (line);
    }
}

template<class T>
T
Parameters<T>::get (const String & name, T defaultValue) const
{
    std::unordered_map<String,String>::const_iterator it = namedValues.find (name);
    if (it == namedValues.end ()) return defaultValue;
    const String & value = it->second;

#   ifdef n2a_FP
    return (T) atoi (value.c_str ());
#   else
    return (T) atof (value.c_str ());
#   endif
}

template<class T>
String
Parameters<T>::get (const String & name, const String & defaultValue) const
{
    std::unordered_map<String,String>::const_iterator it = namedValues.find (name);
    if (it == namedValues.end ()) return defaultValue;
    return it->second;
}


// class IteratorSkip --------------------------------------------------------

template<class T>
IteratorSkip<T>::IteratorSkip (Matrix<T> * A)
:   A (A)
{
    this->row    = -1;
    this->column = 0;
    this->value  = 0;

    nextRow    = -1;
    nextColumn = 0;
    nextValue  = 0;
    getNext ();
}

template<class T>
bool
IteratorSkip<T>::next ()
{
    if (nextRow < 0) return false;
    this->value  = nextValue;
    this->row    = nextRow;
    this->column = nextColumn;
    getNext ();
    return true;
}

template<class T>
void
IteratorSkip<T>::getNext ()
{
    for (; nextColumn < A->columns_; nextColumn++)
    {
        while (true)
        {
            if (++nextRow >= A->rows_) break;
            nextValue = (*A)(nextRow,nextColumn);
            if (nextValue != 0) return;
        }
        nextRow = -1;
    }
}


// class IteratorSparse ------------------------------------------------------

template<class T>
IteratorSparse<T>::IteratorSparse (MatrixSparse<T> * A)
:   A (A)
{
    this->row    = 0;
    this->column = 0;
    this->value  = 0;

    columns = (*A->data).size ();
    if (columns > 0) it = (*A->data)[0].begin ();
}

template<class T>
bool
IteratorSparse<T>::next ()
{
    if (columns == 0) return false;
    while (true)
    {
        if (it != (*A->data)[this->column].end ()) break;
        if (++this->column >= columns) return false;
        it = (*A->data)[this->column].begin ();
    }

    this->row   = it->first;
    this->value = it->second;
    it++;
    return true;
}


// MatrixInput ---------------------------------------------------------------

template<class T>
MatrixInput<T>::MatrixInput (const String & fileName)
:   Holder (fileName)
{
    A = 0;
}

template<class T>
MatrixInput<T>::~MatrixInput ()
{
    if (A) delete A;
}

// Notice that this is not part of MatrixInput. It is a global function.
template<class T>
IteratorNonzero<T> *
getIterator (MatrixAbstract<T> * A)
{
    if (A->classID () & MatrixSparseID) return new IteratorSparse<T> ((MatrixSparse<T> *) A);
    return new IteratorSkip<T> ((Matrix<T> *) A);
}

#ifdef n2a_FP

int
convert (String input, int exponent)
{
    const double d = atof (input.c_str ());
    if (d == 0) return 0;
    if (std::isnan (d)) return NAN;
    bool negate = d < 0;
    if (std::isinf (d))
    {
        if (negate) return -INFINITY;
        return              INFINITY;
    }

    int64_t bits = *(int64_t *) &d;
    int e = (int) ((bits >> 52) & 0x7FF) - 1023;
    bits |= 0x10000000000000l;  // set implied msb of mantissa (bit 52) to 1
    bits &= 0x1FFFFFFFFFFFFFl;  // clear sign and exponent bits
    if (negate) bits = -bits;
    return bits >> 52 - FP_MSB + exponent - e;
}

#endif

template<class T>
MatrixInput<T> *
#ifdef n2a_FP
matrixHelper (const String & fileName, int exponent, MatrixInput<T> * oldHandle)
#else
matrixHelper (const String & fileName,               MatrixInput<T> * oldHandle)
#endif
{
    MatrixInput<T> * handle = (MatrixInput<T> *) SIMULATOR getHolder (fileName, oldHandle);
    if (! handle)
    {
        handle = new MatrixInput<T> (fileName);
        SIMULATOR holders.push_back (handle);

        std::ifstream ifs (fileName.c_str ());
        if (! ifs.good ()) std::cerr << "Failed to open matrix file: " << fileName << std::endl;
        String line;
        getline (ifs, line);
        if (line == "Sparse")  // Homegrown sparse matrix format
        {
            MatrixSparse<T> * S = new MatrixSparse<T>;
            handle->A = S;
            while (ifs.good ())
            {
                getline (ifs, line);
                line.trim ();
                if (line.empty ()) continue;

                String value;
                split (line, ",", value, line);
                value.trim ();
                int row = atoi (value.c_str ());

                split (line, ",", value, line);
                value.trim ();
                int col = atoi (value.c_str ());

                line.trim ();
#               ifdef n2a_FP
                T element = convert (line, exponent);
#               else
                T element = (T) atof (line.c_str ());
#               endif

                if (element) S->set (row, col, element);
            }
        }
        else  // Dense matrix
        {
            // Re-open file to ensure that we get the first line.
            ifs.close ();
            ifs.open (fileName.c_str ());

            std::vector<std::vector<T>> temp;
            std::vector<T> row;
            int columns = 0;
            bool transpose = false;

            // Scan for opening "["
            char token;
            do
            {
                ifs.get (token);
                if (token == '~') transpose = true;
            }
            while (token != '['  &&  ifs.good ());

            // Read rows until closing "]"
            String buffer;
            bool done = false;
            while (ifs.good ()  &&  ! done)
            {
                ifs.get (token);

                bool processLine = false;
                switch (token)
                {
                    case '\r':
                        break;  // ignore CR characters
                    case ' ':
                    case '\t':
                        if (buffer.size () == 0) break;  // ignore leading whitespace (equivalent to trim)
                    case ',':
                        // Process element
                        if (buffer.size () == 0)
                        {
                            row.push_back (0);
                        }
                        else
                        {
#                           ifdef n2a_FP
                            row.push_back (convert (buffer, exponent));
#                           else
                            row.push_back ((T) atof (buffer.c_str ()));
#                           endif
                            buffer.clear ();
                        }
                        break;
                    case ']':
                        done = true;
                    case ';':
                    case '\n':
                    {
                        // Process any final element
                        if (buffer.size () > 0)
                        {
#                           ifdef n2a_FP
                            row.push_back (convert (buffer, exponent));
#                           else
                            row.push_back ((T) atof (buffer.c_str ()));
#                           endif
                            buffer.clear ();
                        }
                        // Process line
                        int c = row.size ();
                        if (c > 0)
                        {
                            temp.push_back (row);  // Duplicates row, rather than saving a reference to it, so row can be reused.
                            columns = std::max (columns, c);
                            row.clear ();
                        }
                        break;
                    }
                    default:
                        buffer += token;
                }
            }

            // Assign elements to A.
            const int rows = temp.size ();
            if (transpose)
            {
                Matrix<T> * A = new Matrix<T> (columns, rows);
                handle->A = A;
                clear (*A);
                for (int r = 0; r < rows; r++)
                {
                    std::vector<T> & row = temp[r];
                    for (int c = 0; c < row.size (); c++)
                    {
                        (*A)(c,r) = row[c];
                    }
                }
            }
            else
            {
                Matrix<T> * A = new Matrix<T> (rows, columns);
                handle->A = A;
                clear (*A);
                for (int r = 0; r < rows; r++)
                {
                    std::vector<T> & row = temp[r];
                    for (int c = 0; c < row.size (); c++)
                    {
                        (*A)(r,c) = row[c];
                    }
                }
            }
        }
        if (handle->A->rows () == 0  ||  handle->A->columns () == 0)
        {
            std::cerr << "Ill-formed matrix in file: " << fileName << std::endl;
            delete handle->A;
            handle->A = new Matrix<T> (1, 1);
            clear (*handle->A); // set to 0
        }
    }
    return handle;
}


// class ImageInput ----------------------------------------------------------

inline String format (String & pattern, int index)
{
    String result;
    result.reserve (pattern.size () + 16);
    sprintf (const_cast<char *> (result.c_str ()), pattern.c_str (), index);
    return result;
}

template<class T>
ImageInput<T>::ImageInput (const String & fileName)
:   Holder (fileName)
{
    index       = 0;
    t           = (T) -INFINITY;
    framePeriod = 0;

    // Determine if fileName is a directory
    String entryName;
#   ifdef _MSC_VER  // Sure would be nice if MSVC followed the POSIX standard ...
    WIN32_FIND_DATA entry;
    HANDLE dir = ::FindFirstFile ((fileName + "/*.*").c_str (), &entry);
    if (dir != INVALID_HANDLE_VALUE)
    {
        entryName = entry.cFileName;
        ::FindClose (dir);
#   else
    DIR * dir = opendir (fileName.c_str ());
    if (dir)
    {
        struct dirent * entry = readdir (dir);
        if (entry) entryName = entry->d_name;
        closedir (dir);
#   endif

        // fileName is a directory
        // We assume that all files in the directory are of the form %d.suffix,
        // where "suffix" specifies a still-image format.
        // Get suffix from first file in directory.
        String prefix;
        String suffix;
        split (entryName, ".", prefix, suffix);
        pattern = fileName;
        pattern += "/%d.";
        pattern += suffix;

        // Detect if sequence is 1-based rather than 0-based.
        struct stat buffer;
        if (stat (format (pattern, 0).c_str (), &buffer)) index = 1;  // Nonzero result from stat() indicates file does not exist.
    }
    else  // fileName is a video file or image sequence specifier
    {
        bool sequence =  fileName.find_first_of ("%") != String::npos;

        // Try to use FFmpeg.
#       ifdef HAVE_FFMPEG
        n2a::VideoFileFormatFFMPEG::use ();
        video = new n2a::VideoIn (fileName);
        if (video->good ())
        {
            if (sequence) framePeriod = atof (video->get ("framePeriod").c_str ());
            return;
        }
        delete video;
        video = 0;
#       endif

        // Fall back on basic image I/O.
        n2a::ImageFileFormatBMP::use ();
        if (sequence)
        {
            pattern = fileName;
            struct stat buffer;
            if (stat (format (pattern, 0).c_str (), &buffer)) index = 1;
        }
        else  // single image
        {
            image.read (fileName);
        }
    }
}

template<class T>
ImageInput<T>::~ImageInput ()
{
#   ifdef HAVE_FFMPEG
    if (video) delete video;
#   endif
}

template<class T>
Matrix<T>
#ifdef n2a_FP
ImageInput<T>::get (String channelName, T now, int exponent)
#else
ImageInput<T>::get (String channelName, T now)
#endif
{
    // Fetch next image, if needed.
#   ifdef HAVE_FFMPEG
    if (video  ||  pattern.size ())
#   else
    if (pattern.size ())
#   endif
    {
        if (std::signbit (now))  // Negative "now" (including negative zero) indicates single step per simulation cycle.
        {
            if (now != t)
            {
                t = now;
                n2a::Image temp;
#               ifdef HAVE_FFMPEG
                if (video)
                {
                    (*video) >> temp;
                }
                else
#               endif
                if (pattern.size ())
                {
                    temp.read (format (pattern, index++));
                }
                if (temp.width)
                {
                    image = temp;
                    channels.clear ();  // Both key and value are regular class instances rather than pointers, so they should automatically destruct and free memory.
                }
            }
        }
        else  // Positive "now" (including positive zero) indicates time or frame number.
        {
            if (now >= t)
            {
                n2a::Image temp;
#               ifdef HAVE_FFMPEG
                if (video)
                {
                    (*video) >> temp;
                    if (temp.width)
                    {
                        double nextPTS = atof (video->get ("nextPTS").c_str ());
                        if (framePeriod) nextPTS /= framePeriod;
#                       ifdef n2a_FP
                        t = (T) (nextPTS * pow (2.0, FP_MSB - Event<T>::exponent));
#                       else
                        t = nextPTS;
#                       endif
                    }
                }
                else
#               endif
                if (pattern.size ())
                {
#                   ifdef n2a_FP
                    index = (int) (now / pow (2.0f, FP_MSB - Event<T>::exponent));
#                   else
                    index = (int) now;
#                   endif
                    temp.read (format (pattern, index));
                    if (temp.width) t = index + 1;
                }
                if (temp.width)
                {
                    image = temp;
                    channels.clear ();
                }
            }
        }
    }
    if (! image.width) return Matrix<T> ();

    // Create converted channels, if needed.
    int colorSpace;
    if      (channelName == "R"  ||  channelName == "G"  ||  channelName == "B" ) colorSpace = 0;
    else if (channelName == "R'" ||  channelName == "G'" ||  channelName == "B'") colorSpace = 1;
    else if (channelName == "X"  ||  channelName == "Y"  ||  channelName == "Z" ) colorSpace = 2;
    else if (channelName == "H"  ||  channelName == "S"  ||  channelName == "V" ) colorSpace = 3;
    else
    {
        colorSpace  = 2;
        channelName = "Y";
    }
    auto it = channels.find (channelName);
    if (it == channels.end ())
    {
        n2a::Image image2;
        String c0, c1, c2;
        switch (colorSpace)
        {
            case 0:
                image2 = image * n2a::RGBFloat;
                c0 = "R"; c1 = "G"; c2 = "B";
                break;
            case 1:
                image2 = image * n2a::sRGBFloat;
                c0 = "R'"; c1 = "G'"; c2 = "B'";
                break;
            case 2:
                image2 = image * n2a::XYZFloat;
                c0 = "X"; c1 = "Y"; c2 = "Z";
                break;
            case 3:
                image2 = image * n2a::HSVFloat;
                c0 = "H"; c1 = "S"; c2 = "V";
                break;
        }

        n2a::Pointer p = ((n2a::PixelBufferPacked *) image2.buffer)->memory;

#       ifdef n2a_FP
        // Convert buffer to int.
        float conversion = pow (2.0f, PP_MSB - exponent);
        int count = image.width * image.height * 3;
        Pointer q (count * sizeof (T));
        float * from = (float *) p
        T *     to   = (T *)     q;
        T *     end  = to + count;
        while (to < end) *to++ = (T) (*from++ * conversion);
        p = q;
#       endif

        channels.emplace (c0, Matrix<T> (p, 0, image.width, image.height, 3, 3 * image.width));
        channels.emplace (c1, Matrix<T> (p, 1, image.width, image.height, 3, 3 * image.width));
        channels.emplace (c2, Matrix<T> (p, 2, image.width, image.height, 3, 3 * image.width));

        it = channels.find (channelName);  // This should always succeed.
    }
    return it->second;
}

template<class T>
ImageInput<T> *
imageInputHelper (const String & fileName, ImageInput<T> * oldHandle)
{
    ImageInput<T> * handle = (ImageInput<T> *) SIMULATOR getHolder (fileName, oldHandle);
    if (! handle)
    {
        handle = new ImageInput<T> (fileName);
        SIMULATOR holders.push_back (handle);
    }
    return handle;
}


// class ImageOutput ---------------------------------------------------------

template<class T>
ImageOutput<T>::ImageOutput (const String & fileName)
:   Holder         (fileName),
    canvas         (n2a::RGBAChar),
    nextProjection (4, 4),
    nextView       (4, 4)
{
    t          = (T) 0;
    frameCount = 0;
    haveData   = false;
    hold       = false;
    dirCreated = false;
    opened     = false;

    width      = 1024;
    height     = 1024;
    clearColor = 0;  // black

    // Register file formats.
    // It does no harm to call use() mutliple times.
    n2a::ImageFileFormatBMP::use ();
#   ifdef HAVE_FFMPEG
    n2a::VideoFileFormatFFMPEG::use ();
    timeScale = 0;
    video     = 0;
#   endif

#   ifdef HAVE_GL
#     ifdef _WIN32
    window = 0;
    dc     = 0;
    rc     = 0;
#     endif
    program       = 0;
    have3D        = false;
    lastWidth     = -1;
    lastHeight    = -1;
    sphereStep    = -1;
    projection.resize (4, 4);
    view      .resize (4, 4);
#   endif
    clear    (nextProjection);
    identity (nextView);
}

template<class T>
ImageOutput<T>::~ImageOutput ()
{
    hold = false;
    try
    {
        writeImage ();
    }
    catch (...)
    {
        std::cerr << "WARNING: last image might have been lost" << std::endl;
    }
#   ifdef HAVE_FFMPEG
    if (video) delete video;
#   endif

    // Clean up GL resources
#   ifdef HAVE_GL
#     ifdef _WIN32
    wglMakeCurrent (0, 0);
    if (rc) wglDeleteContext (rc);  // This frees all GL resources
    if (window)
    {
        ReleaseDC (window, dc);
        DestroyWindow (window);
    }
#     endif
#   endif
}

template<class T>
void
ImageOutput<T>::open ()
{
    opened = true;

    String prefix;
    String suffix;
    int pos = fileName.find_last_of ("\\/");
    if (pos == String::npos)
    {
        prefix = fileName;
        path   = "";
    }
    else
    {
        path   = fileName.substr (0, pos) + "/";
        prefix = fileName.substr (pos + 1);
    }
    pos = prefix.find_last_of ('.');
    if (pos != String::npos)
    {
        suffix = prefix.substr (pos + 1).toLowerCase ();
        prefix = prefix.substr (0, pos);
    }
    int posPercent = prefix.find_last_of ("%");
    if (posPercent != String::npos) prefix = prefix.substr (0, posPercent);
    if (prefix.empty ()) prefix = "frame";

#   ifdef HAVE_FFMPEG
    // Check if this is an image sequence, in which case modify file name to go into subdir.
    String videoFileName;
    if (posPercent == String::npos)  // Single video file
    {
        videoFileName = fileName;
        dirCreated = true;  // Do't create the dir when writing.
    }
    else  // Image sequence, so create a subdirectory. This is more user-friendly for Runs tab.
    {
        path += prefix;
        path += "/";
        videoFileName = path;
        videoFileName += "%d";
        if (! suffix.empty ())
        {
            videoFileName += ".";
            videoFileName += suffix;
        }
    }
    video = new n2a::VideoOut (videoFileName, format, codec);
    if (video->good ()) return;

    // Fall through image sequence code below ...
    delete video;
    video = 0;
#   endif

    if (format.empty ()) format = suffix;
    if (format.empty ()) format = "bmp";
    path += prefix;
    path += "/";  // Include slash in path, so we don't have to add it later. Forward slash works for all platforms.
}

template<class T>
void
ImageOutput<T>::setClearColor (uint32_t color)
{
    clearColor = color << 8 | 0xFF;
#   ifdef HAVE_GL
    cv[0] = ((color & 0xFF0000) >> 16) / 255.0f;
    cv[1] = ((color &   0xFF00) >>  8) / 255.0f;
    cv[2] =  (color &     0xFF)        / 255.0f;
    cv[3] = 1;
#   endif
}

template<class T>
void
ImageOutput<T>::setClearColor (const Matrix<T> & color)
{
    bool hasAlpha = color.rows () > 3;

    uint32_t r =  std::min (1.0f, std::max (0.0f, color[0])) * 255;
    uint32_t g =  std::min (1.0f, std::max (0.0f, color[1])) * 255;
    uint32_t b =  std::min (1.0f, std::max (0.0f, color[2])) * 255;
    uint32_t a = 0xFF;
    if (hasAlpha) std::min (1.0f, std::max (0.0f, color[3])) * 255;
    clearColor = r << 24 | g << 16 | b << 8 | a;

#   ifdef HAVE_GL
    cv[0] = color[0];
    cv[1] = color[1];
    cv[2] = color[2];
    cv[3] = hasAlpha ? color[3] : 1;
#   endif
}

template<class T>
void
ImageOutput<T>::next (T now)
{
    if (now > t)
    {
        writeImage ();
        t = now;
    }
    if (! haveData)
    {
        canvas.resize (width, height);
#       ifdef HAVE_GL
        canvas.clear ();  // transparent black
#       else
        canvas.clear (clearColor);
#       endif
        haveData = true;
    }
}

template<class T>
T
#ifdef n2a_FP
ImageOutput<T>::drawDisc (T now, bool raw, const MatrixFixed<T,3,1> & centerFP, T radiusFP, int exponent, uint32_t color)
#else
ImageOutput<T>::drawDisc (T now, bool raw, const MatrixFixed<T,3,1> & center,   T radius,                 uint32_t color)
#endif
{
    next (now);

#   ifdef n2a_FP
    double conversion = pow (2.0f, FP_MSB - exponent);
    MatrixFixed<double,3,1> center = centerFP;
    center /= conversion;
    double radius = radiusFP / conversion;
#   endif
    uint32_t rgba = color << 8 | 0xFF;

    if (raw)
    {
        if (radius < 0.5) radius = 0.5;  // 1px diameter; causes early-out in CanvasImage routine.
        canvas.scanCircle (center, radius, rgba);
        return 0;
    }

#   ifdef n2a_FP
    n2a::Point cs = center * (double) width;
#   else
    n2a::Point cs = center * (T) width;
#   endif
    radius *= width;
    if (radius < 0.5) radius = 0.5;
    canvas.scanCircle (cs, radius, rgba);
    return 0;
}

template<class T>
T
#ifdef n2a_FP
ImageOutput<T>::drawSquare (T now, bool raw, const MatrixFixed<T,3,1> & centerFP, T wFP, T hFP, int exponent, uint32_t color)
#else
ImageOutput<T>::drawSquare (T now, bool raw, const MatrixFixed<T,3,1> & center,   T w,   T h,                 uint32_t color)
#endif
{
    next (now);

#   ifdef n2a_FP
    double conversion = pow (2.0f, FP_MSB - exponent);
    n2a::Point center = centerFP;
    center /= conversion;
    double w = wFP / conversion;
    double h = hFP / conversion;
#   endif
    uint32_t rgba = color << 8 | 0xFF;

    n2a::Point corner0;
    corner0[0] = center[0] - w / 2;
    corner0[1] = center[1] - h / 2;
    //corner0[2] = center[2];
    n2a::Point corner1;
    corner1[0] = center[0] + w / 2;
    corner1[1] = center[1] + h / 2;
    //corner1[2] = center[2];

    if (! raw)
    {
        corner0 *= (double) width;
        corner1 *= (double) width;
    }

    canvas.drawFilledRectangle (corner0, corner1, rgba);
    return 0;
}

template<class T>
T
#ifdef n2a_FP
ImageOutput<T>::drawSegment (T now, bool raw, const MatrixFixed<T,3,1> & p1FP, const MatrixFixed<T,3,1> & p2FP, T thicknessFP, int exponent, uint32_t color)
#else
ImageOutput<T>::drawSegment (T now, bool raw, const MatrixFixed<T,3,1> & p1,   const MatrixFixed<T,3,1> & p2,   T thickness,                 uint32_t color)
#endif
{
    next (now);

#   ifdef n2a_FP
    double conversion = pow (2.0f, FP_MSB - exponent);
    n2a::Point p1 = p1FP;
    n2a::Point p2 = p2FP;
    p1 /= conversion;
    p2 /= conversion;
    double thickness = thicknessFP / conversion;
#   endif
    uint32_t rgba = color << 8 | 0xFF;

    if (! raw) thickness *= width;
    if (thickness < 1) thickness = 1;
    canvas.setLineWidth (thickness);

    if (raw)
    {
        canvas.drawSegment (p1, p2, rgba);
        return 0;
    }

#   ifdef n2a_FP
    n2a::Point ps1 = p1 * (double) width;
    n2a::Point ps2 = p2 * (double) width;
#   else
    n2a::Point ps1 = p1 * (T) width;
    n2a::Point ps2 = p2 * (T) width;
#   endif
    canvas.drawSegment (ps1, ps2, rgba);
    return 0;
}

template<class T>
void
ImageOutput<T>::writeImage  ()
{
    if (! haveData) return;
    haveData = hold;
    if (hold) return;  // Don't write every frame. hold is set false in dtor, so at least one frame will be written.

#   ifdef HAVE_GL
    int w = canvas.width;
    int h = canvas.height;
    if (have3D)  // Composite 2D and 3D outputs.
    {
        have3D = false;
        uint32_t * pixelData = new uint32_t[w * h];
        glReadPixels (0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixelData);  // always in bottom-up order
        uint32_t * g   = pixelData + (h - 1) * w;  // beginning of last row, which is at the top on screen
        uint32_t * c   = (uint32_t *) canvas.buffer->pixel (0, 0);
        uint32_t * end = to + w * h;
        while (c < end)
        {
            uint32_t * rowEnd = c + w;
            while (c < rowEnd)
            {
#               if BYTE_ORDER == LITTLE_ENDIAN
                alphaBlendOE (*c, *g);
#               else
                alphaBlend (*c, *g);
#               endif
                *c++ = *g++;
            }
            g -= 2 * w;
        }
        delete[] pixelData;
    }
    else  // Fill background with clear color, since this won't be provided by the 3D scene.
    {
#       if BYTE_ORDER == LITTLE_ENDIAN
        uint32_t color = bswap (clearColor);
#       else
        uint32_t color = clearColor;
#       endif
        uint32_t temp;
        uint32_t * c   = (uint32_t *) canvas.buffer->pixel (0, 0);
        uint32_t * end = to + w * h;
        while (c < end)
        {
            uint32_t * rowEnd = c + w;
            while (c < rowEnd)
            {
                temp = color;
#               if BYTE_ORDER == LITTLE_ENDIAN
                alphaBlendOE (*c, temp);
#               else
                alphaBlend (*c, temp);
#               endif
                *c++ = temp;
            }
            g -= 2 * w;
        }
    }
#   endif

    if (! opened) open ();
    if (! dirCreated)
    {
        n2a::mkdirs (path);
        dirCreated = true;
    }

#   ifdef HAVE_FFMPEG
    if (video)
    {
        if (timeScale)
        {
#           ifdef n2a_FP
            canvas.timestamp = timeScale * t / pow (2.0, FP_MSB - Event<T>::exponent);
#           else
            canvas.timestamp = timeScale * t;
#           endif
        }
        else
        {
            canvas.timestamp = 1e6;  // Exceeds 95443, the threshold at which VideoOut stops using the timestamp as PTS.
        }
        (*video) << canvas;
        return;
    }
#   endif

    String filename = path;
    filename += frameCount;
    filename += ".";
    filename += format;
    try
    {
        canvas.write (filename);
    }
    catch (const char * message)  // Our own exception message, generally that file format was not found.
    {
        format = "bmp";  // Our best (and only) fallback.
        filename = path;
        filename += frameCount;
        filename += ".";
        filename += format;
        canvas.write (filename);
    }
    frameCount++;
}

#ifdef HAVE_GL

template<class T>
bool
ImageOutput<T>::next3D (const Matrix<T> & model, const Material & material)
{
    // Plaform-specific create context...
#   ifdef _WIN32

    if (rc == 0)
    {
        window = CreateWindowExW
        (
            0, 0, 0, WS_OVERLAPPED,
            CW_USEDEFAULT, CW_USEDEFAULT, CW_USEDEFAULT, CW_USEDEFAULT,
            NULL, NULL, NULL, NULL
        );
        dc = GetDC (dummy);

        PIXELFORMATDESCRIPTOR desc;
        memset (&desc, 0, sizeof (desc));
        desc.nSize    = sizeof (desc);
        desc.nVersion = 1;
        desc.dwFlags  = PFD_SUPPORT_OPENGL;  // for hardware support
        int format = ChoosePixelFormat (dc, &desc);
        if (! SetPixelFormat (dc, format, &desc)) return false;

        rc = wglCreateContext (dc);
    }
    // There could be multiple contexts (one per output file), so need to make ours current every time.
    // TODO: handle multiple threads
    if (wglGetCurrentContext () != rc) wglMakeCurrent (dc, rc);

    if (! extensionsBound)
    {
#       define X(type, name) name = (type) wglGetProcAddress (#name); if (! name) return false;
        GL_FUNCTIONS(X)
#       undef X
        extensionsBound = true;
    }

#   else  // No menas of creating a GL context.

    return false;

#   endif  // Platform-specific create context.

    // One-time setup per simulation
    if (! program)
    {
        ifstream ifs ("../../backend/c/Shader.vp");  // TODO: need better way of locating runtime resources
        stringstream vp;
        vp << "#version 120" << endl;
        vp << ifs.rdbuf ();
        string vp_string = vp.str ();
        const char * vp_char = vp_string.c_str ();

        GLuint vshader = glCreateShader (GL_VERTEX_SHADER);
        glShaderSource (vshader, 1, &vp_char, 0);
        glCompileShader (vshader);

        GLint info;
        glGetShaderiv (vshader, GL_COMPILE_STATUS, &info);
        if (! info)
        {
            char message[1024];
            glGetShaderInfoLog (vshader, sizeof (message), NULL, message);
            cerr << message << endl;
            return false;
        }

        ifs.close ();
        ifs.open ("../../backend/c/Shader.fp");
        stringstream fp;
        fp << "#version 120" << endl;
        fp << ifs.rdbuf();
        string fp_string = fp.str ();
        const char * fp_char = fp_string.c_str ();

        GLuint fshader = glCreateShader (GL_FRAGMENT_SHADER);
        glShaderSource (fshader, 1, &fp_char, 0);
        glCompileShader (fshader);

        glGetShaderiv (fshader, GL_COMPILE_STATUS, &info);
        if (! info)
        {
            char message[1024];
            glGetShaderInfoLog (fshader, sizeof (message), NULL, message);
            cerr << message << endl;
            return false;
        }

        program = glCreateProgram ();
        glAttachShader (program, fshader);
        glAttachShader (program, vshader);
        glLinkProgram (program);
        glUseProgram (program);

        // create Frame Buffer Object (FBO)
        GLuint fbo;
        glGenFramebuffers (1, &fbo);
        glBindFramebuffer (GL_FRAMEBUFFER, fbo);
        glGenRenderbuffers (1, &rboColor);
        glGenRenderbuffers (1, &rboDepth);

        GLuint vao;
        glGenVertexArrays (1, &vao);
        glBindVertexArray (vao);

        locVertexPosition = glGetAttribLocation (program, "vertexPosition");
        locVertexNormal   = glGetAttribLocation (program, "vertexNormal");

        locMatrixModelView  = glGetUniformLocation (program, "modelViewMatrix");
        locMatrixNormal     = glGetUniformLocation (program, "normalMatrix");
        locMatrixProjection = glGetUniformLocation (program, "projectionMatrix");

        for (int i = 0; i < 8; i++) locLights.push_back (new LightLocation (program, i));
        locEnabled = glGetUniformLocation (program, "enabled");

        Material::locAmbient   = glGetUniformLocation (program, "material.ambient");
        Material::locDiffuse   = glGetUniformLocation (program, "material.diffuse");
        Material::locEmission  = glGetUniformLocation (program, "material.emission");
        Material::locSpecular  = glGetUniformLocation (program, "material.specular");
        Material::locShininess = glGetUniformLocation (program, "material.shininess");

        // setup global GL state
        glEnable (GL_BLEND);
        glBlendFunc (GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glEnable (GL_DEPTH_TEST);
        //glEnable (GL_CULL_FACE);
    }

    if (! have3D)
    {
        int w = canvas.width;
        int h = canvas.height;
        bool haveProjection = norm (nextProjection, 1);
        if (lastWidth != w  ||  lastHeight != h)
        {
            glBindRenderbuffer (GL_RENDERBUFFER, rboColor);
            glRenderbufferStorage (GL_RENDERBUFFER, GL_RGBA8, w, h);
            glFramebufferRenderbuffer (GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rboColor);

            glBindRenderbuffer (GL_RENDERBUFFER, rboDepth);
            glRenderbufferStorage (GL_RENDERBUFFER, GL_DEPTH_COMPONENT, w, h);
            glFramebufferRenderbuffer (GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rboDepth);

            glViewport (0, 0, w, h);

            if (! haveProjection)
            {
                // Create default projection.
                float s = 50e-6f;
                if (w <= h)
                {
                    float r = (float) h / w;
                    projection = glOrtho (-s, s, -s*r, s*r, -s, s);
                }
                else
                {
                    float r = (float) w / h;
                    projection = glOrtho (-s*r, s*r, -s, s, -s, s);
                }
            }

            lastWidth  = canvas.width;
            lastHeight = canvas.height;
        }
        if (haveProjection) copy (projection, nextProjection);
        copy (view, nextView);

        // clear buffer
        glClearColor (cv[0], cv[1], cv[2], cv[3]);
        glClear (GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        // load uniforms
        glUniformMatrix4fv (locMatrixProjection, 1, GL_FALSE, projection.base ());

        if (lights.empty ()) lights.emplace (0);
        int i = 0;
        for (auto l : lights)
        {
            l->setUniform (*locLights[i++], view);
            if (i >= 8) break;
        }
        glUniform1i (locEnabled, i);

        have3D = true;
    }

    // Set up for current drawX() call.
    // This involves setting a couple of uniform values.
    Matrix<float> modelView = view * model;  // The transorm into eye space.
    Matrix<float> normal = modelView;  // TODO: implement inversion, so we can create the inverse transpose of the modelView.
    glUniformMatrix4fv (locMatrixModelView, 1, GL_FALSE, modelView.base ());
    glUniformMatrix4fv (locMatrixNormal, 1, GL_FALSE, normal.base ());
    material.setUniform (*this);

    return true;
}

template<class T>
T
ImageOutput<T>::drawCube (T now, const Matrix<T> & model, const Material & material)
{
    next (now);
    if (! next3D (model, material)) return 0;

    // Set up vertex buffers, if needed.
    std::map<String,GLuint>::iterator it = buffers.find ("cubeVertices");  // We really want contains() here, but don't want to depend on c++20.
    getBuffer ("cubeVertices");
    getBuffer ("cubeIndices");
    if (it == buffers.end ())
    {
        std::vector<GLfloat> vertices (144); // six faces, four vertices per face, 6 floats per vertex
        std::vector<GLuint>  indices  (36);  // six faces, 2 triangles per face, 3 vertices per triangle

        // All vertices are specified in CCW order.

        // Top face, y=1
        GLfloat[3] n;
        n[0] = 0;
        n[1] = 1;
        n[2] = 0;
        put (vertices,  0.5f, 0.5f, -0.5f, n);
        put (vertices, -0.5f, 0.5f, -0.5f, n);
        put (vertices, -0.5f, 0.5f,  0.5f, n);
        put (vertices,  0.5f, 0.5f,  0.5f, n);

        // Bottom face, y=-1
        n[1] = -1;
        put (vertices,  0.5f, -0.5f,  0.5f, n);
        put (vertices, -0.5f, -0.5f,  0.5f, n);
        put (vertices, -0.5f, -0.5f, -0.5f, n);
        put (vertices,  0.5f, -0.5f, -0.5f, n);

        // Front face, z=1
        n[1] = 0;
        n[2] = 1;
        put (vertices,  0.5f,  0.5f,  0.5f, n);
        put (vertices, -0.5f,  0.5f,  0.5f, n);
        put (vertices, -0.5f, -0.5f,  0.5f, n);
        put (vertices,  0.5f, -0.5f,  0.5f, n);

        // Back face, z=-1
        n[2] = -1;
        put (vertices,  0.5f, -0.5f,  -0.5f, n);
        put (vertices, -0.5f, -0.5f,  -0.5f, n);
        put (vertices, -0.5f,  0.5f,  -0.5f, n);
        put (vertices,  0.5f,  0.5f,  -0.5f, n);

        // Left face, x=-1
        n[0] = -1;
        n[2] = 0;
        put (vertices, -0.5f,  0.5f,  0.5f, n);
        put (vertices, -0.5f,  0.5f, -0.5f, n);
        put (vertices, -0.5f, -0.5f, -0.5f, n);
        put (vertices, -0.5f, -0.5f,  0.5f, n);

        // Right face, x=1
        n[0] = 1;
        put (vertices, 0.5f,  0.5f, -0.5f, n);
        put (vertices, 0.5f,  0.5f,  0.5f, n);
        put (vertices, 0.5f, -0.5f,  0.5f, n);
        put (vertices, 0.5f, -0.5f, -0.5f, n);

        for (int v = 0; v < 24; v += 4)
        {
            // first triangle
            indices.push_back (v + 0);
            indices.push_back (v + 1);
            indices.push_back (v + 2);
            // second triangle
            indices.push_back (v + 0);
            indices.push_back (v + 2);
            indices.push_back (v + 3);
        }

        glBufferData (GL_ARRAY_BUFFER,         vertices.size () * sizeof (GLfloat), &vertices[0], GL_STATIC_DRAW);
        glBufferData (GL_ELEMENT_ARRAY_BUFFER, indices .size () * sizeof (GLuint),  &indices [0], GL_STATIC_DRAW);
    }

    glDrawElements (GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0);
    return 0;
}

template<class T>
T
ImageOutput<T>::drawCylinder (T now, const Material & material, const MatrixFixed<T,3,1> & p1, T r1, const MatrixFixed<T,3,1> & p2, T r2, int steps, int stepsCap)
{
    next (now);
    if (! next3D (model, material)) return 0;

    if (p1 == p2) return 0;
    if (r2 < 0) r2 = r1;
    if (r1 == 0  &&  r2 == 0) return 0;
    if (steps < 3) steps = 3;
    if (stepsCap < 0) stepsCap = steps / 4;  // Integer division, so 3/4==0, 4/4==1, etc.

    std::map<String,GLuint>::iterator it = buffers.find ("cylinderVertices");
    getBuffer ("cylinderVertices");
    getBuffer ("cylinderIndices");
    int count = 2 + steps * (2 + stepsCap);  // estimate of vertex count, based on rounded caps at both ends
    std::vector<GLfloat> vertices (count);
    std::vector<GLuint>  indices  (count * 3);

    // Construct a local coordinate frame.
    // This frame will be anchored first at one end (p1), then at the other (p2).
    //   The z vector runs along axis of the cylinder.
    //   Positive direction is toward p1 just because that's how all the
    //   geometry code was developed before moving to flexible coordinates.
    Matrix<float> fz = p1 - p2;
    float length = norm (fz, 2);
    fz /= length;
    //   Create x vector along the axis that z has smallest extent.
    //   This is an arbitrary choice, but it should be the best conitioned when computing cross product.
    Matrix<float> fx (3, 1);
    int dimension = 0;
    for (int i = 1; i < 3; i++) if (abs (fz[i]) < abs (fz[dimension])) dimension = i;
    fx[dimension] = 1;
    //   Create y vector by cross product.
    Matrix<float> fy = normalize (cross (fz, fx));
    fx = normalize (cross (fy, fz));
    Matrix<float> f (3, 4);
    copy (column (f, 0), fx);
    copy (column (f, 1), fy);
    copy (column (f, 2), fz);
    copy (column (f, 3), p1);

    // Determine extra z component for sloped tube (r1 different from r2).
    // Suppose r1 is longer than r2. A triangle is formed by the tip of r1,
    // the tip of r2, and the projection of r1 onto the extension of r2.
    // This triangle is similar to the triangle formed by the tip of r1,
    // the tip of the normal added onto r1, and the slopeZ value we are
    // computing. The ratio between the triangles sizes is
    // 1 (for the normal) / (distance between p1 and p2).
    // This also works when r1 is shorter than r2.
    float slopeZ = (r2 - r1) / length;

    float angleStep    = M_PI * 2 / steps;
    float angleStep2   = M_PI     / steps;  // half of a regular step
    float angleStepCap = M_PI / 2 / (stepsCap + 1);

    int  rowsBegin = 0;     // Index of first vertex in first ring.
    int  rowsEnd   = 0;     // Index of first vertex after last ring.
    int  tip;               // Index of last vertex.
    bool cone1     = false; // Close cone with vertex 0 and the ring that immediately follows it.
    bool cone2     = false; // Close cone with vertex at rowBase1 and the ring the immediately precedes it.

    // Cap 1
    if (r1 > 0)
    {
        if (cap1 == 1)
        {
            cone1 = true;
            rowsBegin = rowsEnd = 1 + steps;  // So we can have separate normals for the disc.
            put (vertices, f, 0, 0, 0, 0, 0, 1);
            for (int i = 0; i < steps; i++)
            {
                float a = i * angleStep;
                float x = cos (a) * r1;
                float y = sin (a) * r1;
                put (vertices, f, x, y, 0, 0, 0, 1);
            }
        }
        else if (cap1 == 2)
        {
            cone1 = true;
            rowsBegin = 1;
            rowsEnd   = 1 + stepsCap * steps;
            put (vertices, f, 0, 0, r1, 0, 0, 1);
            for (int s = stepsCap; s > 0; s--)
            {
                float a = s * angleStepCap;
                float z = sin (a) * r1;
                float r = cos (a) * r1;
                for (int i = 0; i < steps; i++)
                {
                    a = i * angleStep;
                    float x = cos (a) * r;
                    float y = sin (a) * r;
                    float l = sqrt (x * x + y * y + z * z);
                    put (vertices, f, x, y, z, x/l, y/l, z/l);
                }
            }
        }
    }

    // Row 1
    rowsEnd += steps;
    for (int i = 0; i < steps; i++)
    {
        float a = i * angleStep;
        float c = cos (a);
        float s = sin (a);
        float nc = c;
        float ns = s;
        float l = sqrt (1 + slopeZ * slopeZ);  // 1 comes from c*c+s*s
        if (r1 == 0)  // Advance by half a step to get the average norm.
        {
            nc = cos (a + angleStep2);
            ns = sin (a + angleStep2);
            l *= 1000;  // Make the tip normal a lot smaller than the other two. This trick makes smoother shading on the cone.
        }
        put (vertices, f, c*r1, s*r1, 0, nc/l, ns/l, slopeZ/l);
    }

    // Move frame to end point
    copy (column (f, 3), p2);

    // Row 2
    rowsEnd += steps;
    for (int i = 0; i < steps; i++)
    {
        float a = i * angleStep;
        float c = cos (a);
        float s = sin (a);
        float nc = c;
        float ns = s;
        float l = sqrt (1 + slopeZ * slopeZ);
        if (r2 == 0)
        {
            nc = cos (a - angleStep2);
            ns = sin (a - angleStep2);
            l *= 1000;
        }
        put (vertices, f, c*r2, s*r2, 0, nc/l, ns/l, slopeZ/l);
    }

    // Cap 2
    tip = rowsEnd;
    if (r2 > 0)
    {
        if (cap2 == 1)
        {
            cone2 = true;
            tip += steps;
            for (int i = 0; i < steps; i++)
            {
                float a = i * angleStep;
                float x = cos (a) * r2;
                float y = sin (a) * r2;
                put (vertices, f, x, y, 0, 0, 0, -1);
            }
            put (vertices, f, 0, 0, 0, 0, 0, -1);
        }
        else if (cap2 == 2)
        {
            cone2 = true;
            rowsEnd += stepsCap * steps;
            tip = rowsEnd;
            for (int s = 1; s <= stepsCap; s++)
            {
                float a = s * angleStepCap;
                float z = -sin (a) * r2;
                float r =  cos (a) * r2;
                for (int i = 0; i < steps; i++)
                {
                    a = i * angleStep;
                    float x = cos (a) * r;
                    float y = sin (a) * r;
                    float l = sqrt (x * x + y * y + z * z);
                    put (vertices, f, x, y, z, x/l, y/l, z/l);
                }
            }
            put (vertices, f, 0, 0, -r2, 0, 0, -1);
        }
    }

    // Connect vertices into triangles
    if (cone1)
    {
        for (int i = 0; i < steps; i++)
        {
            int j = (i + 1) % steps;
            indices.push_back (0);
            indices.push_back (i + 1);
            indices.push_back (j + 1);
        }
    }
    if (r1 == 0)
    {
        for (int i = 0; i < steps; i++)
        {
            int i1 = rowsBegin + i;               // first vertex in row 1
            int j1 = rowsBegin + (i + 1) % steps; // second vertex in row 1
            int i2 = i1 + steps;                  // first vertex in row 2
            int j2 = j1 + steps;                  // second vertex in row 2

            // Only generate the lower triangle
            indices.push_back (i1);
            indices.push_back (i2);
            indices.push_back (j2);
        }
        rowsBegin += steps;
    }
    else if (r2 == 0)
    {
        rowsEnd -= steps;
        int base = rowsEnd - steps;
        for (int i = 0; i < steps; i++)
        {
            int i1 = base + i;
            int j1 = base + (i + 1) % steps;
            int j2 = j1 + steps;

            // Only generate the upper triangle
            indices.push_back (i1);
            indices.push_back (j2);
            indices.push_back (j1);
        }
    }
    for (int base = rowsBegin; base < rowsEnd - steps; base += steps)
    {
        for (int i = 0; i < steps; i++)
        {
            int i1 = base + i;
            int j1 = base + (i + 1) % steps;
            int i2 = i1 + steps;
            int j2 = j1 + steps;

            indices.push_back (i1);
            indices.push_back (i2);
            indices.push_back (j2);

            indices.push_back (i1);
            indices.push_back (j2);
            indices.push_back (j1);
        }
    }
    if (cone2)
    {
        int ring = tip - steps;
        for (int i = 0; i < steps; i++)
        {
            int j = (i + 1) % steps;
            indices.push_back (tip);
            indices.push_back (ring + j);
            indices.push_back (ring + i);
        }
    }

    count = indices.size ();
    glBufferData (GL_ARRAY_BUFFER,         vertices.size () * sizeof (GLfloat), &vertices[0], GL_STATIC_DRAW);
    glBufferData (GL_ELEMENT_ARRAY_BUFFER, count            * sizeof (GLuint),  &indices [0], GL_STATIC_DRAW);

    glDrawElements (GL_TRIANGLES, count, GL_UNSIGNED_INT, 0);
    return 0;
}

template<class T>
T
ImageOutput<T>::drawPlane (T now, const Matrix<T> & model, const Material & material)
{
    next (now);
    if (! next3D (model, material)) return 0;

    // Set up vertex buffers, if needed.
    std::map<String,GLuint>::iterator it = buffers.find ("planeVertices");
    getBuffer ("planeVertices");
    getBuffer ("planeIndices");
    if (it == buffers.end ())
    {
        std::vector<GLfloat> vertices (24); // four vertices, 6 floats per vertex
        std::vector<GLuint>  indices  (6);  // 2 triangles, 3 vertices per triangle

        float[] n = new float[3];
        n[0] = 0;
        n[1] = 0;
        n[2] = 1;
        put (vertices,  0.5f,  0.5f, 0, n);
        put (vertices, -0.5f,  0.5f, 0, n);
        put (vertices, -0.5f, -0.5f, 0, n);
        put (vertices,  0.5f, -0.5f, 0, n);

        // first triangle
        indices.push_back (0);
        indices.push_back (1);
        indices.push_back (2);

        // second triangle
        indices.push_back (0);
        indices.push_back (2);
        indices.push_back (3);

        glBufferData (GL_ARRAY_BUFFER,         vertices.size () * sizeof (GLfloat), &vertices[0], GL_STATIC_DRAW);
        glBufferData (GL_ELEMENT_ARRAY_BUFFER, indices .size () * sizeof (GLuint),  &indices [0], GL_STATIC_DRAW);
    }

    glDrawElements (GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
    return 0;
}

template<class T>
T
ImageOutput<T>::drawSphere (T now, const Matrix<T> & model, const Material & material, int steps)
{
    next (now);
    if (! next3D (model, material)) return 0;

    if (steps > 10) steps = 10;  // defensive limit. ~20 million faces (20 * 4^10)

    getBuffer ("sphereVertices");
    char name[16];
    if (sphereStep >= step)
    {
        sprintf (name, "sphereIndices%i", step);
        getBuffer ();
    }
    else
    {
        if (sphereStep < 0)
        {
            sphereStep = 0;
            icosphere (sphereVertices, sphereIndices);
            getBuffer ("sphereIndices0");
            glBufferData (GL_ELEMENT_ARRAY_BUFFER, sphereIndices.size () * sizeof (GLuint), &sphereIndices[0], GL_STATIC_DRAW);
        }
        while (sphereStep < step)
        {
            sphereStep++;
            icosphereSubdivide (sphereVertices, sphereIndices);
            sprintf (name, "sphereIndices%i", sphereStep);
            glBufferData (GL_ELEMENT_ARRAY_BUFFER, sphereIndices.size () * sizeof (GLuint), &sphereIndices[0], GL_STATIC_DRAW);
        }
        glBufferData (GL_ARRAY_BUFFER, sphereVertices.size () * sizeof (GLfloat), &sphereVertices[0], GL_STATIC_DRAW);  // This only needs to be uploaded once, since it each step encompasses all the previous ones.
    }

    int count = 60 * pow (4, step);  // 20 triangles in base icosphere * 3 vertices per triangle * 4^subdivisions
    glDrawElements (GL_TRIANGLES, count, GL_UNSIGNED_INT, 0);
    return 0;
}

template<class T>
void
ImageOutput<T>::getBuffer (String name, bool vertices)
{
    std::map<String,GLuint>::iterator it = buffers.find (name);
    bool found =  it != buffers.end ();

    GLuint result;
    if (found)
    {
        result = it.second;
    }
    else
    {
        glGenBuffers (1, &result);
        buffers[name] = result;
    }

    if (vertices)
    {
        glBindBuffer (GL_ARRAY_BUFFER, result);
        if (found) return result;

        glVertexAttribPointer (locVertexPosition, 3, GL_FLOAT, GL_FALSE, 6 * sizeof (GLfloat), 0);
        glEnableVertexAttribArray (locVertexPosition);

        glVertexAttribPointer (locVertexNormal, 3, GL_FLOAT, GL_FALSE, 6 * sizeof (GLfloat), (void *) (3 * sizeof (GLfloat)));
        glEnableVertexAttribArray (locVertexNormal);
    }
    else  // indices
    {
        glBindBuffer (GL_ELEMENT_ARRAY_BUFFER, result);
    }

    return result;
}

#endif  // HAVE_GL

template<class T>
ImageOutput<T> *
imageOutputHelper (const String & fileName, ImageOutput<T> * oldHandle)
{
    ImageOutput<T> * handle = (ImageOutput<T> *) SIMULATOR getHolder (fileName, oldHandle);
    if (! handle)
    {
        handle = new ImageOutput<T> (fileName);
        SIMULATOR holders.push_back (handle);
    }
    return handle;
}


// class Mfile ---------------------------------------------------------------

template<class T>
Mfile<T>::Mfile (const String & fileName)
:   Holder (fileName)
{
    doc = new n2a::MDoc (fileName.c_str ());
}

template<class T>
Mfile<T>::~Mfile ()
{
    if (doc) delete doc;
    for (auto m : matrices) if (m.second) delete m.second;
}

std::vector<String>
keyPath (const std::vector<String> & path)
{
    std::vector<String> result;
    result.reserve (path.size ());  // assuming there are no delimiters
    for (auto & e : path)
    {
        size_t pos   = 0;
        size_t count = e.size ();
        while (pos < count)
        {
            size_t next = e.find_first_of ("/", pos);
            if (next != pos) result.push_back (e.substr (pos, next).c_str ());
            if (next == String::npos) break;  // Need this in case npos is max int. In that case, adding 1 will overflow.
            pos = next + 1;
        }
    }
    return result;
}

template<class T>
MatrixAbstract<T> *
#ifdef n2a_FP
Mfile<T>::getMatrix (const std::vector<String> & path, int exponent)
#else
Mfile<T>::getMatrix (const std::vector<String> & path)
#endif
{
    String key = join ("/", path);
    MatrixAbstract<T> * A = matrices[key];  // If key does not exist, then the c++ standard promises that the inserted value will be zero-initialized.
    if (A) return A;

    MatrixSparse<T> * S = new MatrixSparse<T>;
    n2a::MNode & m = doc->child (path);
    for (auto & row : m)
    {
        int r = atoi (row.key ().c_str ());
        for (auto & col : row)
        {
            int c = atoi (col.key ().c_str ());
            String value = col.get ();
#           ifdef n2a_FP
            S->set (r, c, convert (value, exponent));
#           else
            S->set (r, c, (T) atof (value.c_str ()));
#           endif
        }
    }
    matrices[key] = S;
    return S;
}

template<class T>
Mfile<T> *
MfileHelper (const String & fileName, Mfile<T> * oldHandle)
{
    Mfile<T> * handle = (Mfile<T> *) SIMULATOR getHolder (fileName, oldHandle);
    if (! handle)
    {
        handle = new Mfile<T> (fileName);
        SIMULATOR holders.push_back (handle);
    }
    return handle;
}


// InputHolder ---------------------------------------------------------------

template<class T>
InputHolder<T>::InputHolder (const String & fileName)
:   Holder (fileName)
{
    currentLine      = (T) -1;
    currentValues    = new T[1];
    currentValues[0] = (T) 0;
    currentCount     = 1;
    nextLine         = (T) NAN;
    nextValues       = 0;
    nextCount        = 0;
    A                = 0;
    Alast            = (T) NAN;
    columnCount      = 0;
    timeColumn       = 0;
    timeColumnSet    = false;
    time             = false;
    smooth           = false;
    delimiter        = ' ';
    delimiterSet     = false;
#   ifdef n2a_FP
    epsilon          = 1;
#   else
    epsilon          = (T) 1e-6;
#   endif

    if (fileName.empty ()) in = &std::cin;
    else                   in = new std::ifstream (fileName.c_str ());
}

template<class T>
InputHolder<T>::~InputHolder ()
{
    if (in  &&  in != &std::cin) delete in;
    if (currentValues) delete[] currentValues;
    if (nextValues   ) delete[] nextValues;
    if (A)             delete A;
}

template<class T>
void
InputHolder<T>::getRow (T row)
{
    while (true)
    {
        // Read and process next line
        if (std::isnan (nextLine)  &&  in->good ())
        {
            String line;
            getline (*in, line);
            if (! line.empty ())
            {
                if (! delimiterSet)
                {
                    if      (line.find_first_of ('\t') != String::npos) delimiter = '\t'; // highest precedence
                    else if (line.find_first_of (',' ) != String::npos) delimiter = ',';
                    // space character is lowest precedence
                    delimiterSet =  delimiter != ' '  ||  line.find_first_not_of (' ') != String::npos;
                }

                int tempCount = 1;
                for (auto it : line) if (it == delimiter) tempCount++;
                columnCount = std::max (columnCount, tempCount);

                // Decide whether this is a header row or a value row
                char firstCharacter = line[0];
                if (firstCharacter < '-'  ||  firstCharacter == '/'  ||  firstCharacter > '9')  // not a number, so must be column header
                {
                    // Add any column headers. Generally, these will only be new headers as of this cycle.
                    int index = 0;
                    int i = 0;
                    int end = line.size ();
                    while (i < end)
                    {
                        int j;
                        j = line.find_first_of (delimiter, i);
                        if (j == String::npos) j = end;
                        String header = line.substr (i, j - i);
                        header.trim ();
                        int last = header.size () - 1;
                        if (header[0] == '"'  &&  header[last] == '"') header = header.substr (1, last - 1);
                        if (j > i) columnMap.emplace (header, index);
                        i = j + 1;
                        index++;
                    }

                    // Make column count accessible to other code before first row of data is read.
                    if (! A)
                    {
                        if (time) currentLine = -INFINITY;
                        if (currentCount != columnCount)
                        {
                            delete[] currentValues;
                            currentValues = new T[columnCount];
                            currentCount = columnCount;
                            memset (&currentValues[0], 0, columnCount * sizeof (T));
                        }
                    }

                    // Select time column
                    if (time  &&  ! timeColumnSet)
                    {
                        int timeMatch = 0;
                        for (auto it : columnMap)
                        {
                            int potentialMatch = 0;
                            String header = it.first.toLowerCase ();
                            if      (header == "t"   ) potentialMatch = 2;
                            else if (header == "date") potentialMatch = 2;
                            else if (header == "time") potentialMatch = 3;
                            else if (header == "$t"  ) potentialMatch = 4;
                            else if (header.find ("time") != String::npos) potentialMatch = 1;
                            if (potentialMatch > timeMatch)
                            {
                                timeMatch = potentialMatch;
                                timeColumn = it.second;
                            }
                        }
                        timeColumnSet = true;
                    }

                    continue;  // back to top of outer while loop, skipping any other processing below
                }

                if (nextCount < columnCount)
                {
                    if (nextValues) delete[] nextValues;
                    nextValues = new T[columnCount];
                    nextCount = columnCount;
                }
                int index = 0;
                int i = 0;
                for (; index < tempCount; index++)
                {
                    int j;
                    j = line.find_first_of (delimiter, i);
                    if (j == String::npos) j = line.size ();
                    if (j == i)
                    {
                        nextValues[index] = 0;
                    }
                    else  // j > i
                    {
                        String field = line.substr (i, j - i);

                        // Special case for ISO 8601 formatted date
                        // Convert date to Unix time. Dates before epoch will be negative.
                        bool valid = false;
                        if (index == timeColumn)
                        {
                            int year   = 1970;  // will be adjusted below for mktime()
                            int month  = 1;     // ditto
                            int day    = 1;
                            int hour   = 0;
                            int minute = 0;
                            int second = 0;

                            int length = field.size ();
                            if (length == 4)
                            {
                                year  = atoi (field.c_str ());
                                valid =  year < 3000  &&  year > 1000;
                            }
                            else if (length >= 7  &&  field[4] == '-')
                            {
                                valid = true;
                                year  = atoi (field.substr (0, 4).c_str ());
                                month = atoi (field.substr (5, 2).c_str ());
                                if (length >= 10  &&  field[7] == '-')
                                {
                                    day = atoi (field.substr (8, 2).c_str ());
                                    if (length >= 13  &&  field[10] == 'T')
                                    {
                                        hour = atoi (field.substr (11, 2).c_str ());
                                        if (length >= 16  &&  field[13] == ':')
                                        {
                                            minute = atoi (field.substr (14, 2).c_str ());
                                            if (length >= 19  &&  field[16] == ':')
                                            {
                                                second = atoi (field.substr (17, 2).c_str ());
                                            }
                                        }
                                    }
                                }
                            }

                            if (valid)
                            {
                                month -= 1;
                                year  -= 1900;

                                struct tm date;
                                date.tm_isdst = 0;  // time is strictly UTC, with no DST
                                // ignoring tm_wday and tm_yday, as mktime() doesn't do anything with them

                                // Hack to adjust for mktime() that can't handle dates before posix epoch (1970/1/1).
                                // This simple hack only works for years after ~1900.
                                // Solution comes from https://bugs.php.net/bug.php?id=17123
                                // Alternate solution would be to implement a simple mktime() right here.
                                // Since we don't care about DST or timezones, all it has to do is handle Gregorion leap-years.
                                time_t offset = 0;
                                if (year <= 70)  // Yes, that includes 1970 itself.
                                {
                                    // The referenced post suggested 56 years, which apparently makes week days align correctly.
                                    year += 56;
                                    date.tm_year = 70 + 56;
                                    date.tm_mon  = 0;
                                    date.tm_mday = 1;
                                    date.tm_hour = 0;
                                    date.tm_min  = 0;
                                    date.tm_sec  = 0;
                                    offset = mktime (&date);
                                }

                                date.tm_year = year;
                                date.tm_mon  = month;
                                date.tm_mday = day;
                                date.tm_hour = hour;
                                date.tm_min  = minute;
                                date.tm_sec  = second;

                                nextValues[index] = mktime (&date) - offset;  // Unix time; an integer, so exponent=MSB
#                               ifdef n2a_FP
                                // Need to put value in expected exponent.
                                int shift = FP_MSB - (time ? Event<T>::exponent : exponent);
                                if (shift >= 0) nextValues[index] <<= shift;
                                else            nextValues[index] >>= -shift;
#                               endif
                            }
                        }

                        if (! valid)  // Not a date, so general case ...
                        {
#                           ifdef n2a_FP
                            nextValues[index] = convert (field, time  &&  index == timeColumn ? Event<T>::exponent : exponent);
#                           else
                            nextValues[index] = (T) atof (field.c_str ());
#                           endif
                        }
                    }
                    i = j + 1;
                }
                for (; index < columnCount; index++) nextValues[index] = 0;

                if (time) nextLine = nextValues[timeColumn];
                else      nextLine = currentLine + 1;
            }
        }

        // Determine if we have the requested data
        if (row <= currentLine) break;
        if (std::isnan (nextLine)) break;  // Return the current line, because another is not (yet) available. In general, we don't stall the simulator to wait for data.
        if (row < nextLine - epsilon) break;

        T * tempValues = currentValues;
        int tempCount  = currentCount;
        currentLine   = nextLine;
        currentValues = nextValues;
        currentCount  = nextCount;
        nextLine   = (T) NAN;
        nextValues = tempValues;
        nextCount  = tempCount;
    }
}

template<class T>
T
InputHolder<T>::get (T row, const String & column)
{
    getRow (row);
    std::unordered_map<String,int>::const_iterator it = columnMap.find (column);
    if (it == columnMap.end ()) return 0;

#   ifdef n2a_FP
    if (smooth  &&  row >= currentLine  &&  currentLine != -INFINITY  &&  nextLine != NAN)
    {
        // We don't need to know what exponent the line values have, as long as they match.
        int b = ((int64_t) (row - currentLine) << FP_MSB) / (nextLine - currentLine);
        int b1 = (1 << FP_MSB) - b;
        return (int64_t) b * nextValues[it->second] + (int64_t) b1 * currentValues[it->second] >> FP_MSB;
    }
#   else
    if (smooth  &&  row >= currentLine  &&  std::isfinite (currentLine)  &&  std::isfinite (nextLine))
    {
        T b = (row - currentLine) / (nextLine - currentLine);
        return b * nextValues[it->second] + (1-b) * currentValues[it->second];
    }
#   endif

    return currentValues[it->second];
}

template<class T>
T
InputHolder<T>::get (T row, T column)
{
    getRow (row);
    int c = (int) round (column);
    if (time  &&  c >= timeColumn) c++;  // time column is not included in raw index
    if      (c < 0            ) c = 0;
    else if (c >= currentCount) c = currentCount - 1;

#   ifdef n2a_FP
    if (smooth  &&  row >= currentLine  &&  currentLine != -INFINITY  &&  nextLine != NAN)
    {
        int b  = ((int64_t) (row - currentLine) << FP_MSB) / (nextLine - currentLine);
        int b1 = (1 << FP_MSB) - b;
        return (int64_t) b * nextValues[c] + (int64_t) b1 * currentValues[c] >> FP_MSB;
    }
#   else
    if (smooth  &&  row >= currentLine  &&  std::isfinite (currentLine)  &&  std::isfinite (nextLine))
    {
        T b = (row - currentLine) / (nextLine - currentLine);
        return b * nextValues[c] + (1-b) * currentValues[c];
    }
#   endif

    return currentValues[c];
}

template<class T>
Matrix<T>
InputHolder<T>::get (T row)
{
    getRow (row);

#   ifdef n2a_FP
    if (smooth  &&  row >= currentLine  &&  currentLine != -INFINITY  &&  nextLine != NAN)
    {
        if (Alast == row) return *A;

        // Create a new matrix
        if (A) delete A;
        int b  = ((int64_t) (row - currentLine) << FP_MSB) / (nextLine - currentLine);
        int b1 = (1 << FP_MSB) - b;
        if (currentCount > 1)
        {
            int columns = currentCount - 1;
            A = new Matrix<T> (1, columns);
            int from = 0;
            for (int to = 0; to < columns; to++)
            {
                if (from == timeColumn) from++;
                (*A)(0,to) = (int64_t) b * nextValues[from] + (int64_t) b1 * currentValues[from] >> FP_MSB;
                from++;
            }
        }
        else
        {
            A = new Matrix<T> (1, 1);
            (*A)(0,0) = (int64_t) b * nextValues[0] + (int64_t) b1 * currentValues[0] >> FP_MSB;
        }

        Alast = row;
        return *A;
    }
#   else
    if (smooth  &&  row >= currentLine  &&  std::isfinite (currentLine)  &&  std::isfinite (nextLine))
    {
        if (Alast == row) return *A;

        // Create a new matrix
        if (A) delete A;
        T b  = (row - currentLine) / (nextLine - currentLine);
        T b1 = 1 - b;
        if (currentCount > 1)
        {
            int columns = currentCount - 1;
            A = new Matrix<T> (1, columns);
            int from = 0;
            for (int to = 0; to < columns; to++)
            {
                if (from == timeColumn) from++;
                (*A)(0,to) = b * nextValues[from] + b1 * currentValues[from];
                from++;
            }
        }
        else
        {
            A = new Matrix<T> (1, 1);
            (*A)(0,0) = b * nextValues[0] + b1 * currentValues[0];
        }

        Alast = row;
        return *A;
    }
#   endif

    if (Alast == currentLine) return *A;

    // Create a new matrix
    if (A) delete A;
    if (time  &&  currentCount > 1)
    {
        int columns = currentCount - 1;
        A = new Matrix<T> (1, columns);
        int from = 0;
        for (int to = 0; to < columns; to++)
        {
            if (from == timeColumn) from++;
            (*A)(0,to) = currentValues[from++];
        }
    }
    else
    {
        A = new Matrix<T> (currentValues, 0, 1, currentCount, currentCount, 1);
    }
    Alast = currentLine;
    return *A;
}

template<class T>
InputHolder<T> *
#ifdef n2a_FP
inputHelper (const String & fileName, int exponent, InputHolder<T> * oldHandle)
#else
inputHelper (const String & fileName,               InputHolder<T> * oldHandle)
#endif
{
    InputHolder<T> * handle = (InputHolder<T> *) SIMULATOR getHolder (fileName, oldHandle);
    if (! handle)
    {
        handle = new InputHolder<T> (fileName);
        SIMULATOR holders.push_back (handle);
#       ifdef n2a_FP
        handle->exponent = exponent;
#       endif
    }
    return handle;
}


// OutputHolder --------------------------------------------------------------

template<class T>
OutputHolder<T>::OutputHolder (const String & fileName)
:   Holder (fileName)
{
    columnsPrevious = 0;
    traceReceived   = false;
    t               = 0;
    raw             = false;

    if (fileName.empty ())
    {
        out = &std::cout;
        columnFileName = "out.columns";
    }
    else
    {
        out = new std::ofstream (fileName.c_str ());
        columnFileName = fileName + ".columns";
    }
}

template<class T>
OutputHolder<T>::~OutputHolder ()
{
    if (out)
    {
        try
        {
            writeTrace ();
        }
        catch (...)
        {
            std::cerr << "WARNING: final trace values might have been lost" << std::endl;
        }
        out->flush ();
        if (out != &std::cout) delete out;

        try
        {
            writeModes ();
        }
        catch (...)
        {
            std::cerr << "WARNING: column info might have been lost" << std::endl;
        }
    }
    for (auto it : columnMode) delete it;
}

template<class T>
void
OutputHolder<T>::trace (T now)
{
    // Detect when time changes and dump any previously traced values.
    if (now > t)
    {
        writeTrace ();
        t = now;
    }

    if (! traceReceived)  // First trace for this cycle
    {
        if (columnValues.empty ())  // slip $t into first column
        {
            columnMap["$t"] = 0;
#           ifdef n2a_FP
            columnValues.push_back ((float) t / pow (2.0f, FP_MSB - Event<T>::exponent));
#           else
            columnValues.push_back (t);
#           endif
            columnMode.push_back (new std::map<String,String>);
        }
        else
        {
#           ifdef n2a_FP
            columnValues[0] = (float) t / pow (2.0f, FP_MSB - Event<T>::exponent);
#           else
            columnValues[0] = t;
#           endif
        }
        traceReceived = true;
    }
}

template<class T>
void
OutputHolder<T>::addMode (const char * mode)
{
    std::map<String,String> * result = new std::map<String,String>;
    columnMode.push_back (result);
    if (mode)
    {
        String rest = mode;
        String hint;
        while (! rest.empty ())
        {
            split (rest, ",", hint, rest);
            hint.trim ();
            String key;
            String value;
            split (hint, "=", key, value);
            if (key == "timeScale")
            {
                std::map<String,String> * c = columnMode[0];
                (*c)["scale"] = value;
            }
            else if (key == "ymin"  ||  key == "ymax"  ||  key == "xmin"  ||  key == "xmax")
            {
                std::map<String,String> * c = columnMode[0];
                (*c)[key] = value;
            }
            else
            {
                (*result)[key] = value;
            }
        }
    }
}

template<class T>
T
#ifdef n2a_FP
OutputHolder<T>::trace (T now, const String & column, T valueFP, int exponent, const char * mode)
#else
OutputHolder<T>::trace (T now, const String & column, T value,                 const char * mode)
#endif
{
    trace (now);

#   ifdef n2a_FP
    float value;
    if      (valueFP ==  NAN)      value =  std::numeric_limits<float>::quiet_NaN ();
    else if (valueFP ==  INFINITY) value =  std::numeric_limits<float>::infinity ();
    else if (valueFP == -INFINITY) value = -std::numeric_limits<float>::infinity ();
    else                           value = (float) valueFP / pow (2.0f, FP_MSB - exponent);
#   endif

    std::unordered_map<String, int>::iterator result = columnMap.find (column);
    if (result == columnMap.end ())
    {
        columnMap[column] = columnValues.size ();
        columnValues.push_back ((float) value);
        addMode (mode);
    }
    else
    {
        columnValues[result->second] = (float) value;
    }

#   ifdef n2a_FP
    return valueFP;
#   else
    return value;
#   endif
}

#ifdef n2a_FP

template<class T>
Matrix<T>
OutputHolder<T>::trace (T now, const String & column, const Matrix<T> & A, int exponent, const char * mode)
{
    int rows = A.rows ();
    int cols = A.columns ();
    if (rows == 1)
    {
        for (int c = 0; c < cols; c++) trace (now, column + "(" + c + ")", A(0,c), exponent, mode);
    }
    else if (cols == 1)
    {
        for (int r = 0; r < rows; r++) trace (now, column + "(" + r + ")", A(r,0), exponent, mode);
    }
    else
    {
        for (int r = 0; r < rows; r++)
        {
            for (int c = 0; c < cols; c++)
            {
                trace (now, column + "(" + r + "," + c + ")", A(r,c), exponent, mode);
            }
        }
    }

    return A;
}

#else

template<class T>
Matrix<T>
OutputHolder<T>::trace (T now, const String & column, const Matrix<T> & A, const char * mode)
{
    int rows = A.rows ();
    int cols = A.columns ();
    if (rows == 1)
    {
        for (int c = 0; c < cols; c++) trace (now, column + "(" + c + ")", A(0,c), mode);
    }
    else if (cols == 1)
    {
        for (int r = 0; r < rows; r++) trace (now, column + "(" + r + ")", A(r,0), mode);
    }
    else
    {
        for (int r = 0; r < rows; r++)
        {
            for (int c = 0; c < cols; c++)
            {
                trace (now, column + "(" + r + "," + c + ")", A(r,c), mode);
            }
        }
    }

    return A;
}

#endif

template<class T>
T
#ifdef n2a_FP
OutputHolder<T>::trace (T now, T columnFP, T valueFP, int exponent, const char * mode)
#else
OutputHolder<T>::trace (T now, T column,   T value,                 const char * mode)
#endif
{
    trace (now);

#   ifdef n2a_FP
    float column = (float) columnFP / pow (2.0f, FP_MSB - 15);  // column has fixed exponent of 15
    float value;
    if      (valueFP ==  NAN)      value =  std::numeric_limits<float>::quiet_NaN ();
    else if (valueFP ==  INFINITY) value =  std::numeric_limits<float>::infinity ();
    else if (valueFP == -INFINITY) value = -std::numeric_limits<float>::infinity ();
    else                           value = (float) valueFP / pow (2.0f, FP_MSB - exponent);
#   endif

    String columnName;
    int index;  // Only used for "raw" mode.
    if (raw) columnName = index = (int) round (column);
    else     columnName = column;

    std::unordered_map<String, int>::iterator result = columnMap.find (columnName);
    if (result == columnMap.end ())
    {
        if (raw)
        {
            index++;  // column index + offset for time column
            columnValues.resize (index, std::numeric_limits<float>::quiet_NaN ());  // add any missing columns before the one we are about to create
        }
        columnMap[columnName] = columnValues.size ();
        columnValues.push_back ((float) value);
        addMode (mode);
    }
    else
    {
        columnValues[result->second] = (float) value;
    }

#   ifdef n2a_FP
    return valueFP;
#   else
    return value;
#   endif
}

template<class T>
void
OutputHolder<T>::writeTrace ()
{
    if (! traceReceived  ||  ! out) return;  // Don't output anything unless at least one value was set.

    const int count = columnValues.size ();
    const int last  = count - 1;

    // Write headers if new columns have been added
    if (count > columnsPrevious)
    {
        if (! raw)
        {
            std::vector<String> headers (count);
            for (auto it : columnMap) headers[it.second] = it.first;

            (*out) << headers[0];  // Should be $t
            int i = 1;
            for (; i < columnsPrevious; i++)
            {
                (*out) << "\t";
            }
            for (; i < count; i++)
            {
                (*out) << "\t";
                String header (headers[i]);  // deep copy
                header.replace_all (' ', '_');
                (*out) << header;
            }
            (*out) << std::endl;
        }
        columnsPrevious = count;
        writeModes ();
    }

    // Write values
    float NANf = std::numeric_limits<float>::quiet_NaN ();  // Necessary because "NAN" might be an integer.
    for (int i = 0; i <= last; i++)
    {
        float & c = columnValues[i];
        if (! std::isnan (c)) (*out) << c;
        if (i < last) (*out) << "\t";
        c = NANf;
    }
    (*out) << std::endl;

    traceReceived = false;
}

template<class T>
void
OutputHolder<T>::writeModes ()
{
    std::ofstream mo (columnFileName.c_str ());
    mo << "N2A.schema=3\n";
    for (auto it : columnMap)
    {
        int i = it.second;
        mo << i << ":" << it.first << "\n";
        auto mode = columnMode[i];
        for (auto nv : *mode) mo << " " << nv.first << ":" << nv.second << "\n";
    }
    // mo should automatically flush and close here
}

template<class T>
OutputHolder<T> *
outputHelper (const String & fileName, OutputHolder<T> * oldHandle)
{
    OutputHolder<T> * handle = (OutputHolder<T> *) SIMULATOR getHolder (fileName, oldHandle);
    if (! handle)
    {
        handle = new OutputHolder<T> (fileName);
        SIMULATOR holders.push_back (handle);
    }
    return handle;
}


#endif
