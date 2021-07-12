/*****************************************************************************/
/* We are really trying to keep it to a bare minimum when it comes to C/C++
 * dependencies. We need the runtime to compile to webassembly, and each
 * additional dependency makes it more challenging.
 */
/*****************************************************************************/
#define SK_FTABLE_SIZE 64

#ifndef SKIP_RUNTIME
#define SKIP_RUNTIME 1

#define NULL ((void*)0)

#define PAGE_SIZE (512 * 1024)
#define STACK_INIT_CAPACITY (1024)

typedef unsigned char uint8_t;
typedef unsigned short uint16_t;
typedef long long uint64_t;
typedef unsigned int uint32_t;
typedef unsigned long size_t;
typedef uint64_t SkipInt;

#ifdef SKIP32
typedef unsigned int uintptr_t;
typedef int intptr_t;
#define WORDSIZE 4
#endif

#ifdef SKIP64
#include <stdio.h>
#define WORDSIZE 8
typedef unsigned long uintptr_t;
typedef long intptr_t;
#endif

/*****************************************************************************/
/* Types used for the Obstack pages info. */
/*****************************************************************************/

typedef struct {
  void* head;
  size_t size;
} sk_page_t;

typedef struct {
  sk_page_t* pages;
  long pages_size;
} sk_pinfo_t;

/*****************************************************************************/
/* Types used for the hashtable. */
/*****************************************************************************/

#define TOMB ((uint64_t)-1)

typedef struct {
  void* key;
  uint64_t value;
} sk_cell_t;

typedef struct {
  size_t size;
  size_t rsize;
  size_t bitcapacity;
  sk_cell_t* data;
} sk_htbl_t;

void sk_htbl_init(sk_htbl_t* table, size_t bitcapacity);
void sk_htbl_free(sk_htbl_t* table);
void sk_htbl_add(sk_htbl_t* table, void* key, uint64_t value);
sk_cell_t* sk_htbl_find(sk_htbl_t* table, void* key);
int sk_htbl_mem(sk_htbl_t* table, void* key);
void sk_htbl_remove(sk_htbl_t* table, void* key);
SkipInt SKIP_String_cmp(unsigned char* str1, unsigned char* str2);
size_t sk_get_nbr_pages();
sk_cell_t* sk_get_pages(size_t size);
size_t sk_get_obstack_idx(char* ptr, sk_cell_t* pages, size_t size);

/*****************************************************************************/
/* Stack types. */
/*****************************************************************************/

typedef struct {
  void** value;
  void** slot;
} sk_value_t;

typedef struct {
  size_t head;
  size_t capacity;
  sk_value_t* values;
} sk_stack_t;

void sk_stack_init(sk_stack_t* st, size_t capacity);
void sk_stack_free(sk_stack_t* st);
void sk_stack_push(sk_stack_t* st, void** value, void** slot);
sk_value_t sk_stack_pop(sk_stack_t* st);

/*****************************************************************************/
/* Stack3 types. */
/*****************************************************************************/

typedef struct {
  void* value1;
  void* value2;
  void* value3;
} sk_value3_t;

typedef struct {
  size_t head;
  size_t capacity;
  sk_value3_t* values;
} sk_stack3_t;

void sk_stack3_init(sk_stack3_t* st, size_t capacity);
void sk_stack3_free(sk_stack3_t* st);
void sk_stack3_push(sk_stack3_t* st, void* value1, void* value2, void* value3);
sk_value3_t sk_stack3_pop(sk_stack3_t* st);

/*****************************************************************************/
/* The type information exposed by the Skip compiler for each object. */
/*****************************************************************************/

typedef struct {
  uint8_t m_refsHintMask;
  uint8_t m_kind;
  uint8_t m_tilesPerMask;
  uint8_t m_hasName;
  uint16_t m_uninternedMetadataByteSize;
  uint16_t m_internedMetadataByteSize;
  size_t m_userByteSize;
  #ifdef SKIP32
  void* unused1;
  void* unused2;
  #endif
  void (*m_onStateChange)(void*, long);
  size_t m_refMask[0];
} SKIP_gc_type_t;

/*****************************************************************************/
/* SKIP String representation. */
/*****************************************************************************/

typedef struct {
  uint32_t size;
  uint32_t hash;
  unsigned char data[0];
} sk_string_t;

/*****************************************************************************/
/* SKIP linked list. */
/*****************************************************************************/

typedef struct {
  void* head;
  void* tail;
} sk_list_t;

/*****************************************************************************/
/* Function signatures. */
/*****************************************************************************/

int memcmp(const void * ptr1, const void * ptr2, size_t num);
void* memcpy(void* dest, const void* src, size_t size);
void* memset(void *, int, unsigned long);

char* SKIP_Obstack_alloc(size_t size);
uint32_t SKIP_String_byteSize(char* str);
char* SKIP_context_get();
void* SKIP_copy_with_pages(void* obj, size_t nbr_pages, sk_cell_t* pages);
SkipInt SKIP_getArraySize(char*);
char* SKIP_get_free_slot(uint32_t);
void* SKIP_intern(void* obj);
void* SKIP_intern_shared(void* obj);
void SKIP_internalExit();
void SKIP_invalid_utf8();
SkipInt SKIP_isEq(char* obj1, char* obj2);
uint32_t SKIP_is_string(char* obj);
void SKIP_print_char(uint32_t);
uint32_t SKIP_read_line_fill();
uint32_t SKIP_read_line_get(uint32_t);
void SKIP_syncContext(uint64_t, char* context, char* obj);
void SKIP_throw(void*);

void sk_add_ftable(void* ptr, size_t size);
void sk_commit();
char* sk_context_get_unsafe();
void sk_context_set(char* obj);
void sk_context_set_unsafe(char* obj);
uintptr_t sk_decr_ref_count(void*);
void sk_free_size(void*, size_t);
void sk_free_root(char* obj);
void* sk_get_ftable(size_t size);
void sk_global_lock();
void sk_global_unlock();
void sk_incr_ref_count(void*);
int sk_is_const(void*);
int sk_is_large_page(char* page);
int sk_is_static(void*);
void* sk_malloc(size_t size);
void* sk_malloc(size_t);
char* sk_new_const(char* cst);
void sk_obstack_attach_page(char* lpage);
size_t sk_page_size(char* page);
void* sk_palloc(size_t size);
void sk_persist_consts();
void sk_pfree_size(void*, size_t);
size_t sk_pow2_size(size_t);
void sk_print_int(SkipInt);
void sk_staging();
char* sk_string_create(const char* buffer, uint32_t size);
void throw_Invalid_utf8();
void todo();
char* sk_get_external_pointer();
char* sk_get_external_pointer_destructor(char* obj);
SkipInt sk_get_external_pointer_value(char* obj);
void sk_call_external_pointer_descructor(char*, SkipInt);
int sk_is_nofile_mode();
void sk_lower_static(void*);
void sk_check_has_lock();
void sk_free_obj(sk_stack_t* st, char* obj);
void sk_free_external_pointers();
uintptr_t sk_get_ref_count(void* obj);
void SKIP_throwInvalidSynchronization();

#endif
